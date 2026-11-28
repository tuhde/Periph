import sigrokdecode as srd

# Device select 1010 E2 1 1: E2 = 0 -> 0x53 (user memory, dynamic registers,
# mailbox), E2 = 1 -> 0x57 (system configuration). Two address bytes (high, low).
ADDR_USER = 0x53
ADDR_SYSTEM = 0x57
ADDRS = (ADDR_USER, ADDR_SYSTEM)
PAGE = 256

SYS_REGS = {
    0x0000: 'GPO', 0x0001: 'IT_TIME', 0x0002: 'EH_MODE', 0x0003: 'RF_MNGT',
    0x0004: 'RFA1SS', 0x0005: 'ENDA1', 0x0006: 'RFA2SS', 0x0007: 'ENDA2',
    0x0008: 'RFA3SS', 0x0009: 'ENDA3', 0x000A: 'RFA4SS', 0x000B: 'I2CSS',
    0x000C: 'LOCK_CCFILE', 0x000D: 'MB_MODE', 0x000E: 'MB_WDG', 0x000F: 'LOCK_CFG',
    0x0010: 'LOCK_DSFID', 0x0011: 'LOCK_AFI', 0x0012: 'DSFID', 0x0013: 'AFI',
    0x0014: 'MEM_SIZE[0]', 0x0015: 'MEM_SIZE[1]', 0x0016: 'BLK_SIZE', 0x0017: 'IC_REF',
    0x0018: 'UID[0]', 0x0019: 'UID[1]', 0x001A: 'UID[2]', 0x001B: 'UID[3]',
    0x001C: 'UID[4]', 0x001D: 'UID[5]', 0x001E: 'UID[6]', 0x001F: 'UID[7]',
    0x0020: 'IC_REV',
}
DYN_REGS = {
    0x2000: 'GPO_CTRL_Dyn', 0x2002: 'EH_CTRL_Dyn', 0x2003: 'RF_MNGT_Dyn',
    0x2004: 'I2C_SSO_Dyn', 0x2005: 'IT_STS_Dyn', 0x2006: 'MB_CTRL_Dyn', 0x2007: 'MB_LEN_Dyn',
}
MAILBOX = 0x2008
MAILBOX_END = 0x2107
I2C_PWD = 0x0900

GPO_BITS = ['RF_USER_EN', 'RF_ACTIVITY_EN', 'RF_INTERRUPT_EN', 'FIELD_CHANGE_EN',
            'RF_PUT_MSG_EN', 'RF_GET_MSG_EN', 'RF_WRITE_EN', 'GPO_EN']
IT_STS_BITS = ['RF_USER', 'RF_ACTIVITY', 'RF_INTERRUPT', 'FIELD_FALLING',
               'FIELD_RISING', 'RF_PUT_MSG', 'RF_GET_MSG', 'RF_WRITE']
EH_CTRL_BITS = ['EH_EN', 'EH_ON', 'FIELD_ON', 'VCC_ON', None, None, None, None]
MB_CTRL_BITS = ['MB_EN', 'HOST_PUT_MSG', 'RF_PUT_MSG', None, 'HOST_MISS_MSG',
                'RF_MISS_MSG', 'HOST_CURRENT_MSG', 'RF_CURRENT_MSG']
IC_REFS = {0x24: 'ST25DV04K', 0x26: 'ST25DV16K/64K'}

ANN_MEM_WRITE = 0
ANN_MEM_READ = 1
ANN_REG_WRITE = 2
ANN_REG_READ = 3
ANN_MAILBOX = 4
ANN_PASSWORD = 5
ANN_ACK_POLL = 6
ANN_FIELDS = 7
ANN_WARNING = 8


def _hexs(data):
    return ' '.join('0x%02X' % b for b in data)


def _bits(value, names):
    set_names = [n for i, n in enumerate(names) if n and value & (1 << i)]
    return ' | '.join(set_names) if set_names else 'none'


def _reg_name(addr, reg):
    if addr == ADDR_SYSTEM:
        if reg == I2C_PWD:
            return 'I2C_PWD'
        return SYS_REGS.get(reg, 'SYS[0x%04X]' % reg)
    if reg in DYN_REGS:
        return DYN_REGS[reg]
    if MAILBOX <= reg <= MAILBOX_END:
        return 'MB_Dyn[%d]' % (reg - MAILBOX)
    return 'MEM[0x%04X]' % reg


class Decoder(srd.Decoder):
    api_version = 3
    id = 'st25dv'
    name = 'ST25DV'
    longname = 'ST25DV04K/16K/64K dynamic NFC/RFID tag'
    desc = 'Decode ST25DV I2C user memory, dynamic registers, mailbox, system area and password commands.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['st25dv']
    tags = ['IC', 'RFID']

    annotations = (
        ('mem-write', 'User memory write'),
        ('mem-read', 'User memory read'),
        ('reg-write', 'Register write'),
        ('reg-read', 'Register read'),
        ('mailbox', 'Mailbox (fast transfer mode)'),
        ('password', 'I2C password command'),
        ('ack-poll', 'ACK polling'),
        ('fields', 'Decoded register fields'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_MEM_WRITE, ANN_MEM_READ, ANN_REG_WRITE, ANN_REG_READ,
                          ANN_MAILBOX, ANN_PASSWORD, ANN_ACK_POLL)),
        ('status', 'Status', (ANN_FIELDS,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.addr = None
        self.is_read = False
        self.reg_ptr = None
        self.addr_hi = None
        self.databuf = []
        self.ss_block = None
        self.ss_addr = None
        self.cur = {ADDR_USER: None, ADDR_SYSTEM: None}
        self.awaiting_write_done = {ADDR_USER: False, ADDR_SYSTEM: False}
        self.addr_pending = False
        self.password_presented = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _ann(self, cls, texts, ss=None, es=None):
        self.put(self.ss_block if ss is None else ss, self.es if es is None else es,
                 self.out_ann, [cls, texts])

    def _py(self, kind, value):
        self.put(self.ss_block, self.es, self.out_python, (kind, value))

    def _warn(self, msg, tag, ss=None, es=None):
        self._ann(ANN_WARNING, [msg, tag], ss, es)

    def _fields(self, addr, reg, data):
        """Decode bit fields of known registers covered by [reg, reg + len(data))."""
        for i, v in enumerate(data):
            r = reg + i
            name = _reg_name(addr, r)
            text = None
            if addr == ADDR_SYSTEM and r == 0x0000 or addr == ADDR_USER and r == 0x2000:
                text = _bits(v, GPO_BITS)
            elif addr == ADDR_USER and r == 0x2005:
                text = _bits(v, IT_STS_BITS)
            elif addr == ADDR_USER and r == 0x2002:
                text = _bits(v, EH_CTRL_BITS)
            elif addr == ADDR_USER and r == 0x2006:
                text = _bits(v, MB_CTRL_BITS)
            elif addr == ADDR_USER and r == 0x2004:
                text = 'session open' if v & 0x01 else 'session closed'
            elif addr == ADDR_USER and r == 0x2007:
                text = 'message %d bytes' % (v + 1)
            elif addr == ADDR_SYSTEM and r == 0x0017:
                text = IC_REFS.get(v, 'unknown IC_REF')
            elif addr == ADDR_SYSTEM and r == 0x0016:
                text = 'block %d bytes' % (v + 1)
            elif addr == ADDR_SYSTEM and r == 0x0001:
                text = 'GPO pulse %.1f us' % (301 - (v & 0x07) * 37.65)
            elif addr == ADDR_SYSTEM and r == 0x000E:
                w = v & 0x07
                text = 'watchdog off' if w == 0 else 'watchdog %d ms' % (2 ** (w - 1) * 30)
            elif addr == ADDR_SYSTEM and r in (0x0005, 0x0007, 0x0009):
                text = 'area %d ends at 0x%04X' % ((r - 0x0005) // 2 + 1, v * 32 + 31)
            if text is not None:
                self._ann(ANN_FIELDS, ['%s: %s' % (name, text), '%s %s' % (name, text[:12]), name[:8]])
                self._py('FIELD', (name, v, text))
        if addr == ADDR_SYSTEM and reg <= 0x0014 and reg + len(data) >= 0x0017:
            lo = data[0x0014 - reg]
            hi = data[0x0015 - reg]
            blk = data[0x0016 - reg]
            size = ((lo | hi << 8) + 1) * (blk + 1)
            self._ann(ANN_FIELDS, ['MEM_SIZE: %d blocks x %d bytes = %d bytes' % ((lo | hi << 8) + 1, blk + 1, size),
                                   'MEM %d B' % size, 'MEM'])
        if addr == ADDR_SYSTEM and reg <= 0x0018 and reg + len(data) >= 0x0020:
            uid = data[0x0018 - reg:0x0020 - reg][::-1]
            self._ann(ANN_FIELDS, ['UID: %s' % ' '.join('%02X' % b for b in uid), 'UID', 'UID'])

    def _finish(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_ADDR_LO', 'GET_ADDR_HI'):
            return
        addr = self.addr
        buf = self.databuf
        if self.is_read:
            if not buf:
                return
            start = self.reg_ptr if self.reg_ptr is not None else self.cur.get(addr)
            if start is None:
                start = 0
            name = _reg_name(addr, start)
            if addr == ADDR_USER and start < 0x2000:
                cls = ANN_MEM_READ
            elif addr == ADDR_USER and start >= MAILBOX:
                cls = ANN_MAILBOX
            else:
                cls = ANN_REG_READ
            self._ann(cls, ['Read %s (0x%04X) +%d: %s' % (name, start, len(buf) - 1, _hexs(buf)),
                            'R %s %s' % (name, _hexs(buf[:4])), 'R %04X' % start])
            self._py('REG_READ', (addr, start, bytes(buf)))
            self._fields(addr, start, buf)
            self.cur[addr] = start + len(buf)
            return
        if self.reg_ptr is None:
            if self.addr_hi is not None:
                self._warn('Incomplete 16-bit address (1 byte)', 'ADDR?')
            return
        reg = self.reg_ptr
        name = _reg_name(addr, reg)
        if not buf:
            self._ann(ANN_REG_WRITE, ['Pointer → %s (0x%04X)' % (name, reg), 'PTR 0x%04X' % reg, 'PTR'])
            self._py('REG_WRITE', (addr, reg, b''))
            self.cur[addr] = reg
            return
        if addr == ADDR_SYSTEM and reg == I2C_PWD:
            self._password(buf)
            return
        if addr == ADDR_USER and MAILBOX <= reg <= MAILBOX_END:
            self._ann(ANN_MAILBOX, ['Mailbox put %d bytes: %s' % (len(buf), _hexs(buf)),
                                    'MB put %d' % len(buf), 'MB']
                      )
            self._py('MAILBOX_WRITE', bytes(buf))
            if reg != MAILBOX:
                self._warn('Mailbox write must start at 0x2008 (chip NACKs)', 'MB?')
            if len(buf) > 256:
                self._warn('Mailbox message longer than 256 bytes', 'LEN?')
            return
        cls = ANN_MEM_WRITE if addr == ADDR_USER and reg < 0x2000 else ANN_REG_WRITE
        self._ann(cls, ['Write %s (0x%04X) +%d: %s' % (name, reg, len(buf) - 1, _hexs(buf)),
                        'W %s %s' % (name, _hexs(buf[:4])), 'W %04X' % reg])
        self._py('REG_WRITE', (addr, reg, bytes(buf)))
        self._fields(addr, reg, buf)
        self.cur[addr] = reg + len(buf)
        eeprom = addr == ADDR_SYSTEM or reg < 0x2000
        if addr == ADDR_USER and reg < 0x2000:
            if (reg % PAGE) + len(buf) > PAGE:
                self._warn('Write crosses a 256-byte page boundary (wraps inside the page)', 'PAGE?')
            if len(buf) > 256:
                self._warn('More than 256 data bytes in one write', 'LEN?')
        if addr == ADDR_SYSTEM and not self.password_presented:
            self._warn('System-area write without a preceding I2C password presentation (chip NACKs)', 'PWD?')
        if eeprom:
            self.awaiting_write_done[addr] = True

    def _password(self, buf):
        if len(buf) != 17:
            self._warn('I2C password command with %d data bytes (expected 17)' % len(buf), 'LEN?')
            return
        code = buf[8]
        same = buf[:8] == buf[9:]
        if code == 0x09:
            self._ann(ANN_PASSWORD, ['Present I2C password ******** (validation 0x09)', 'Present PWD', 'PWD'])
            self._py('PASSWORD_PRESENT', None)
            self.password_presented = True
        elif code == 0x07:
            self._ann(ANN_PASSWORD, ['Write I2C password ******** (validation 0x07)', 'Write PWD', 'PWD'])
            self._py('PASSWORD_WRITE', None)
            self.awaiting_write_done[ADDR_SYSTEM] = True
            if not self.password_presented:
                self._warn('Password change without a preceding password presentation', 'PWD?')
        else:
            self._warn('Unknown password validation code 0x%02X' % code, 'BAD?')
        if not same:
            self._warn('The two password copies differ: the chip ignores the command', 'PWD?')

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('ACK', 'NACK') and self.addr_pending:
            self.addr_pending = False
            addr = self.addr
            if ptype == 'NACK':
                if self.awaiting_write_done.get(addr):
                    self._ann(ANN_ACK_POLL, ['ACK poll 0x%02X: no ACK (write cycle running)' % addr, 'poll NACK', 'NACK'],
                              self.ss_addr, es)
                else:
                    self._warn('Device select 0x%02X NACKed (RF access or low-power down)' % addr, 'NACK',
                               self.ss_addr, es)
                self.state = 'IDLE'
            elif self.awaiting_write_done.get(addr):
                self.awaiting_write_done[addr] = False
                self._ann(ANN_ACK_POLL, ['ACK poll 0x%02X: write cycle done' % addr, 'poll ACK', 'ACK'],
                          self.ss_addr, es)
            return

        if ptype in ('START', 'START REPEAT'):
            if (ptype == 'START REPEAT' and self.state in ('GET_DATA_WRITE',) and
                    self.reg_ptr is not None and not self.databuf):
                pass  # random read: keep the address set by the write phase
            else:
                self._finish()
                self.databuf = []
                self.is_read = False
                self.reg_ptr = None
                self.addr_hi = None
                self.ss_block = ss
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.ss_addr = ss
            self.addr_pending = True
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.databuf = []
                self.state = 'GET_DATA_READ'
            else:
                self.state = 'GET_ADDR_HI'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_ADDR_HI':
                self.addr_hi = pdata
                self.state = 'GET_ADDR_LO'
            elif self.state == 'GET_ADDR_LO':
                self.reg_ptr = (self.addr_hi << 8) | pdata
                self.addr_hi = None
                self.databuf = []
                self.state = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish()
            self.state = 'IDLE'
            self.databuf = []
            self.reg_ptr = None
            self.addr_hi = None
