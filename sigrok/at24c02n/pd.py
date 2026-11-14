import sigrokdecode as srd

# Device-select byte 1010 A2 A1 A0 -> 0x50-0x57; one address byte.
ADDRS = set(range(0x50, 0x58))
SIZE = 0x0100
PAGE = 8

ANN_MEM_WRITE = 0
ANN_MEM_READ  = 1
ANN_PTR_WRITE = 2
ANN_ACK_POLL  = 3
ANN_WARNING   = 4
# Conformance-only annotations (specs/testing_framework.md, "Conformance
# Implementation" and specs/memory/at24c02n_timing.conf): write_cycle_start
# at the STOP of a data write, write_cycle_done at the first device-select
# byte that the chip ACKs afterwards (ACK polling). Additive to the generic
# annotations so PulseView manual-verification rows are unaffected.
ANN_WRITE_CYCLE_START = 5
ANN_WRITE_CYCLE_DONE  = 6


def _hexs(data):
    return ' '.join('0x%02X' % b for b in data)


def _warn_tag(msg):
    """Short (<= 5 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'chip id' in m or 'device id' in m:
        return 'ID?'
    if 'address' in m:
        return 'ADDR?'
    if any(k in m for k in ('length', 'byte', 'short', 'missing', 'expected', 'no data', 'empty')):
        return 'LEN?'
    if any(k in m for k in ('unknown', 'unexpected', 'invalid', 'undefined', 'reserved', 'out of range')):
        return 'BAD?'
    return 'WARN'


def _with_short(strings):
    """Data/status annotations carry >= 3 tiers (long, medium, short); if a
    call site supplied fewer, derive the missing tier from the existing text
    (leading name token, <= 8 chars when it becomes the shortest tier) so a
    narrow PulseView zoom still has something to show."""
    strings = list(strings)
    if len(strings) >= 3 or not strings:
        return strings

    def lead(text):
        text = text.strip()
        n = 0
        while n < len(text) and (text[n].isalnum() or text[n] in '_\u2192'):
            n += 1
        return text[:n]

    first = strings[0]
    words = first.split()
    for cand in (lead(strings[-1])[:8], lead(first), ' '.join(words[:2]).rstrip(':,'),
                 ' '.join(words[:3]).rstrip(':,'), lead(first)[:8]):
        if len(strings) >= 3:
            break
        if cand and cand not in strings and len(cand) < len(first):
            i = 0
            while i < len(strings) and len(strings[i]) > len(cand):
                i += 1
            strings.insert(i, cand)
    return strings


class Decoder(srd.Decoder):
    api_version = 3
    id = 'at24c02n'
    name = 'AT24C02N'
    longname = 'AT24C02N 2 Kbit I2C EEPROM'
    desc = 'Decode AT24C02N I2C EEPROM byte/page writes, random/sequential/current-address reads and ACK polling.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['at24c02n']
    tags = ['IC', 'Memory']

    annotations = (
        ('mem-write',  'Memory write'),
        ('mem-read',   'Memory read'),
        ('ptr-write',  'Address pointer write'),
        ('ack-poll',   'ACK polling'),
        ('warning',    'Warning'),
        ('write-cycle-start', 'Write cycle start (conformance: write_cycle start)'),
        ('write-cycle-done',  'Write cycle done (conformance: write_cycle end)'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_MEM_WRITE, ANN_MEM_READ, ANN_PTR_WRITE, ANN_ACK_POLL)),
        ('timing',   'Timing',   (ANN_WRITE_CYCLE_START, ANN_WRITE_CYCLE_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann:
            tiered = self.__dict__.get('_tiered')
            if tiered is None:
                tiered = self._tiered = {
                    c for rid, _title, classes in self.annotation_rows
                    if rid not in ('timing', 'warnings') for c in classes}
            if data[0] in tiered:
                data = [data[0], _with_short(data[1])]
                self._mirror_python(ss, es)
        super().put(ss, es, out, data)

    def _mirror_python(self, ss, es):
        """OUTPUT_PYTHON mirror of each transaction-level data annotation:
        ('REG_READ' | 'REG_WRITE', (register, bytes)), or
        ('I2C_READ' | 'I2C_WRITE', (address, bytes)) for chips without a register pointer."""
        out_py = self.__dict__.get('out_python')
        if out_py is None or self.__dict__.get('_py_span') == (ss, es):
            return
        self._py_span = (ss, es)
        try:
            buf = bytes(b & 0xFF for b in getattr(self, 'databuf', None) or ())
        except TypeError:
            return
        rw = 'READ' if getattr(self, 'is_read', False) else 'WRITE'
        reg = getattr(self, 'reg_ptr', None)
        if reg is None:
            reg = getattr(self, 'reg_byte', None)
        if reg is not None:
            super().put(ss, es, out_py, ('REG_' + rw, (reg, buf)))
        else:
            super().put(ss, es, out_py, ('I2C_' + rw, (getattr(self, 'addr', None), buf)))

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None       # 8-bit memory address set by the write phase
        self.databuf  = []
        self.ss_block = None
        self.ss_addr  = None
        self.cur      = None       # address pointer after the previous access
        self.awaiting_write_done = False
        self.poll_pending = False  # a device-select byte after a write, waiting for its ACK/NACK
        self.poll_noted = False    # the ACK/NACK of a device-select-only transaction was already annotated

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_PTR'):
            return
        buf = self.databuf
        if self.is_read:
            if not buf:
                return
            start = self.reg_ptr if self.reg_ptr is not None else self.cur
            if start is None:
                start = 0
            kind = 'Random read' if self.reg_ptr is not None else 'Current-address read'
            if len(buf) == 1:
                text = ['%s [0x%02X] = 0x%02X' % (kind, start, buf[0]), 'R 0x%02X 0x%02X' % (start, buf[0])]
            else:
                text = ['Sequential read [0x%02X] +%d: %s' % (start, len(buf) - 1, _hexs(buf)), 'SeqRead 0x%02X +%d' % (start, len(buf) - 1)]
            self.put(self.ss_block, self.es, self.out_ann, [ANN_MEM_READ, text])
            if start + len(buf) > SIZE:
                self._warn(self.ss_block, self.es, 'Read past 0x00FF wraps to 0x0000')
            self.cur = (start + len(buf)) % SIZE
            return
        # write phase
        if self.reg_ptr is None:
            if self.state == 'GET_PTR' and not self.poll_noted:
                self.put(self.ss_block, self.es, self.out_ann, [ANN_ACK_POLL, ['ACK poll: device select only', 'ACK?']])
            return
        reg = self.reg_ptr
        if not buf:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_PTR_WRITE, ['Pointer \u2192 0x%02X' % reg, 'PTR 0x%02X' % reg]])
            self.cur = reg
            return
        n = len(buf)
        if n == 1:
            text = ['Byte write [0x%02X] = 0x%02X' % (reg, buf[0]), 'W 0x%02X 0x%02X' % (reg, buf[0])]
        else:
            text = ['Page write [0x%02X] +%d: %s' % (reg, n - 1, _hexs(buf)), 'PageW 0x%02X +%d' % (reg, n - 1)]
        self.put(self.ss_block, self.es, self.out_ann, [ANN_MEM_WRITE, text])
        if (reg % PAGE) + n > PAGE:
            self._warn(self.ss_block, self.es,
                       'Write crosses a %d-byte page boundary: wraps inside the page and overwrites earlier bytes' % PAGE)
        if n > PAGE:
            self._warn(self.ss_block, self.es, 'More than %d data bytes in one write' % PAGE)
        if reg + n > SIZE:
            self._warn(self.ss_block, self.es, 'Write past 0x00FF out of range')
        self.cur = (reg + min(n, PAGE)) % SIZE
        self.put(self.ss_block, self.es, self.out_ann,
                 [ANN_WRITE_CYCLE_START, ['write_cycle_start: STOP issued, internal EEPROM write cycle begins', 'write_cycle_start', 'WC\u25b6']])
        self.awaiting_write_done = True

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype == 'ACK' and self.poll_pending:
            self.poll_pending = False
            self.poll_noted = True
            if self.awaiting_write_done:
                self.put(self.ss_addr, es, self.out_ann,
                         [ANN_WRITE_CYCLE_DONE, ['write_cycle_done: chip ACKed its address again', 'write_cycle_done', 'WC\u2713']])
                self.awaiting_write_done = False
            return
        if ptype == 'NACK' and self.poll_pending:
            self.poll_pending = False
            self.poll_noted = True
            self.put(self.ss_addr, es, self.out_ann, [ANN_ACK_POLL, ['ACK poll: no ACK (write cycle running)', 'poll NACK', 'NACK']])
            return

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_DATA_WRITE' and self.reg_ptr is not None and not self.databuf:
                pass   # random read: keep the address set by the write phase
            else:
                self._finish_transaction()
                self.databuf = []
                self.is_read = False
                self.reg_ptr = None
                self.ss_block = ss
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = addr
            self.ss_addr = ss
            self.poll_noted = False
            self.is_read = (ptype == 'ADDRESS READ')
            if self.awaiting_write_done:
                self.poll_pending = True
            if self.is_read:
                self.databuf = []
                self.state = 'GET_DATA_READ'
            else:
                self.state = 'GET_PTR'

        elif ptype == 'DATA WRITE':
            byte = pdata
            if self.state == 'GET_PTR':
                self.reg_ptr = byte
                self.databuf = []
                self.state = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(byte)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []
            self.reg_ptr = None
            self.poll_noted = False
