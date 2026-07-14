import sigrokdecode as srd

# The 24AA02UID has no address pins (A0/A1/A2 not connected), so it responds
# to any address in the 0x50-0x57 range. The 24AA025UID variant (different
# chip, same family) uses the address pins for bus selection.
ADDRS = set(range(0x50, 0x58))

# The 24AA02UID has a 256-byte memory map, not a register map. The decoder
# treats each byte address as a "register" so it can annotate random-read
# transactions with the target address. The upper block (0x80-0xFF) has
# special meaning and is decoded separately.
REGS = {a: '0x%02X' % a for a in range(0x00, 0x100)}

ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_PTR_WRITE = 2
ANN_WARNING   = 3


def _decode_read(addr, data):
    if addr >= 0xFA:
        # The upper block is permanently write-protected and contains
        # the manufacturer code, device code, and 32-bit serial number.
        if 0xFA <= addr <= 0xFB:
            return _decode_static_field(addr, data)
        if 0xFC <= addr <= 0xFF:
            return _decode_serial(addr, data)
        return 'Reserved 0x%02X 0x%02X' % (addr, data)
    return 'Mem[0x%02X] 0x%02X' % (addr, data)


def _decode_static_field(addr, data):
    if addr == 0xFA:
        ok = ' (Microchip ✓)' if data == 0x29 else ' (expected 0x29!)'
        return 'MFR 0x%02X%s' % (data, ok)
    if addr == 0xFB:
        ok = ' (24AA02UID ✓)' if data == 0x41 else ' (expected 0x41!)'
        return 'DEV 0x%02X%s' % (data, ok)
    return '[0x%02X] 0x%02X' % (addr, data)


def _decode_serial(start_addr, data):
    # data is the first byte of a sequential read; the rest of the UID
    # bytes arrive on subsequent DATA READ events in databuf. Caller
    # assembles the 4-byte UID.
    return 'UID byte @ 0x%02X = 0x%02X' % (start_addr, data)


def _assemble_uid(start_addr, databuf):
    n = len(databuf)
    if n < 4:
        return 'UID @ 0x%02X partial (%d/4) = %s' % (
            start_addr, n, ' '.join('0x%02X' % b for b in databuf))
    uid = (databuf[0] << 24) | (databuf[1] << 16) | (databuf[2] << 8) | databuf[3]
    return 'UID 0x%02X%02X%02X%02X (0x%08X, %u)' % (
        databuf[0], databuf[1], databuf[2], databuf[3], uid, uid)


def _is_wp_region(addr, length):
    if length <= 0:
        return False
    end = addr + length - 1
    return addr >= 0x80 or end >= 0x80


class Decoder(srd.Decoder):
    api_version = 3
    id = '24aa02uid'
    name = '24AA02UID'
    longname = '24AA02UID 2K I2C EEPROM with 32-bit unique serial number'
    desc = 'Decode 24AA02UID I2C EEPROM byte/page writes, random and sequential reads, and the upper (read-only) UID block at 0xFA-0xFF.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['24aa02uid']
    tags = ['IC', 'Memory']

    annotations = (
        ('mem-write',  'Memory write'),
        ('mem-read',   'Memory read'),
        ('ptr-write',  'Address pointer write'),
        ('warning',    'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_PTR_WRITE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None
        self.databuf  = []
        self.ss_block = None
        self.ss_seq   = None   # start of the current sequential read
        self.seq_addr = None   # start address of the current sequential read

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg]])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return

        reg  = self.reg_ptr
        name = REGS.get(reg, '0x%02X' % reg) if reg is not None else '?'

        if self.is_read:
            n = len(self.databuf)
            if reg is not None and 0xFC <= reg <= 0xFF and n >= 4:
                # Random-read of the 4-byte UID (or a portion of it that
                # covers 0xFC-0xFF when starting at 0xFC).
                uid_bytes = list(self.databuf[:4])
                uid_int = (uid_bytes[0] << 24) | (uid_bytes[1] << 16) \
                        | (uid_bytes[2] << 8)  |  uid_bytes[3]
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_READ,
                          ['Read UID 0x%02X%02X%02X%02X (0x%08X, %u)' % (
                              uid_bytes[0], uid_bytes[1], uid_bytes[2], uid_bytes[3],
                              uid_int, uid_int),
                           'R UID 0x%08X' % uid_int]])
                if n > 4:
                    self._warn(self.ss_block, self.es,
                               'Read past UID end (%d extra bytes)' % (n - 4))
            elif n == 1:
                byte = self.databuf[0]
                if reg is not None and 0xFC <= reg <= 0xFF:
                    # Partial UID read at one address.
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_REG_READ,
                              ['Read %s: %s' % (name, _decode_serial(reg, byte)),
                               'R %s 0x%02X' % (name, byte)]])
                else:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_REG_READ,
                              ['Read %s: %s' % (name, _decode_read(reg, byte)),
                               'R %s 0x%02X' % (name, byte)]])
            else:
                self._warn(self.ss_block, self.es,
                           'Unexpected read length %d for %s' % (n, name))
        else:
            if reg is None or not self.databuf:
                # Pure pointer set (no data bytes) → pointer write annotation.
                if reg is not None:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_PTR_WRITE,
                              ['Pointer → 0x%02X' % reg,
                               'PTR 0x%02X' % reg]])
            else:
                # Byte or page write at `reg` (with `reg` as start address).
                descs_short = []
                descs_long  = []
                wp = _is_wp_region(reg, len(self.databuf))
                for i, b in enumerate(self.databuf):
                    addr = reg + i
                    descs_short.append('0x%02X' % b)
                    descs_long.append('[0x%02X]=0x%02X' % (addr, b))
                tail = '  ⚠ write-protected' if wp else ''
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_REG_WRITE,
                          ['Write %s +%d: %s%s' % (name, len(self.databuf) - 1, ' '.join(descs_long), tail),
                           'W %s %s' % (name, ' '.join(descs_short))]])
                if wp:
                    self._warn(self.ss_block, self.es,
                               'Write to write-protected region 0x%02X-0x%02X' %
                               (reg, reg + len(self.databuf) - 1))

    def _finish_sequential(self):
        """Called on STOP after a sequential read (no register pointer)."""
        if not self.databuf:
            return
        if self.seq_addr is not None and 0xFC <= self.seq_addr <= 0xFF \
                and len(self.databuf) >= 4:
            # UID read spanning 0xFC-0xFF (or starting at 0xFC).
            desc = _assemble_uid(self.seq_addr, self.databuf)
            short = 'UID 0x%08X' % (
                (self.databuf[0] << 24) | (self.databuf[1] << 16)
                | (self.databuf[2] << 8) | self.databuf[3])
            self.put(self.ss_seq, self.es, self.out_ann,
                     [ANN_REG_READ, [desc, short]])
        else:
            n = len(self.databuf)
            start = self.seq_addr if self.seq_addr is not None else 0
            head = 'Sequential read @ 0x%02X +%d: ' % (start, n - 1)
            short = 'SeqRead 0x%02X +%d' % (start, n - 1)
            self.put(self.ss_seq, self.es, self.out_ann,
                     [ANN_REG_READ,
                      [head + ' '.join('0x%02X' % b for b in self.databuf),
                       short]])

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state in ('GET_REG_PTR', 'GET_DATA_WRITE') and self.reg_ptr is not None and not self.databuf:
                # Random read: address pointer set (no data bytes yet),
                # repeated start, read. Keep reg_ptr and reset databuf.
                self.databuf = []
            else:
                if self.is_read and self.state == 'GET_DATA_READ' and self.reg_ptr is None:
                    # Sequential / current-address read in progress; finish
                    # on STOP, not on RESTART.
                    self._finish_sequential()
                else:
                    self._finish_transaction()
                self.databuf  = []
                self.is_read  = False
                self.ss_block = ss
                self.ss_seq   = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr    = addr
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                if self.reg_ptr is not None:
                    # Random read: read the single byte at `reg_ptr`.
                    self.databuf = []
                    self.state   = 'GET_DATA_READ'
                else:
                    # Sequential / current-address read.
                    if self.seq_addr is None:
                        self.seq_addr = 0
                    self.ss_seq   = ss
                    self.databuf  = []
                    self.state    = 'GET_DATA_READ'
            else:
                self.state = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            byte = pdata
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = byte
                self.seq_addr = byte
                self.databuf = []
                self.state   = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(byte)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)
                # Auto-increment the current address pointer for sequential reads.
                if self.seq_addr is not None:
                    self.seq_addr = (self.seq_addr + 1) & 0xFF

        elif ptype == 'STOP':
            if self.is_read and self.state == 'GET_DATA_READ' and self.reg_ptr is None:
                self._finish_sequential()
            else:
                self._finish_transaction()
            self.state   = 'IDLE'
            self.databuf = []
            self.seq_addr = None
