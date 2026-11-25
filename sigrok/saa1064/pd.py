import sigrokdecode as srd

ADDRS = set(range(0x38, 0x3C))

ANN_CMD = 0
ANN_DATA = 1
ANN_WARNING = 2

SEG_NAMES = 'abcdefg.'
DEFAULT_CHARS = {0x3F: '0', 0x06: '1', 0x5B: '2', 0x4F: '3', 0x66: '4',
                 0x6D: '5', 0x7D: '6', 0x07: '7', 0x7F: '8', 0x6F: '9', 0x00: ' '}


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
    id = 'saa1064'
    name = 'SAA1064'
    longname = 'SAA1064 4-digit LED driver'
    desc = 'Decode SAA1064 I2C instruction, control, digit and status transfers.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['saa1064']
    tags = ['IC', 'Display', 'LED']

    annotations = (
        ('cmd',     'Instruction / control'),
        ('data',    'Digit / status data'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_CMD, ANN_DATA)),
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
        self.sub      = None
        self.databuf  = []
        self.ss_block = None
        self.ss       = None
        self.es       = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    @staticmethod
    def _control_label(c):
        ma = 3 * (c >> 4 & 1) + 6 * (c >> 5 & 1) + 12 * (c >> 6 & 1)
        return 'mode %s, digits 1+3 %s, digits 2+4 %s, test %s, %d mA' % (
            'dynamic' if c & 1 else 'static',
            'on' if c & 2 else 'blanked', 'on' if c & 4 else 'blanked',
            'on' if c & 8 else 'off', ma)

    @staticmethod
    def _digit_label(b):
        segs = ''.join(SEG_NAMES[i] for i in range(7) if b >> i & 1)
        label = '0x%02X [%s%s]' % (b, segs, ' dp' if b & 0x80 else '')
        ch = DEFAULT_CHARS.get(b & 0x7F)
        if ch is not None:
            label += ' = %s%s' % (ch, '.' if b & 0x80 else '')
        return label

    def _finish_transaction(self):
        if self.state not in ('GET_SUB', 'GET_DATA', 'GET_READ'):
            return
        ss, es = self.ss_block, self.es
        if self.is_read:
            if not self.databuf:
                return
            st = self.databuf[0]
            self.put(ss, es, self.out_ann, [ANN_DATA, [
                'Status: 0x%02X, PR (power reset) %d' % (st, st >> 7 & 1), 'Status']])
            return
        if self.sub is None:
            return
        sub = self.sub
        if sub > 4:
            self._warn(ss, es, 'Invalid subaddress 0x%02X (valid 0x00-0x04)' % sub)
            return
        auto = ' (auto-increment)' if len(self.databuf) > 1 else ''
        self.put(ss, es, self.out_ann, [ANN_CMD, [
            'Subaddress 0x%02X (%s)%s' % (sub, 'control' if sub == 0 else 'digit %d' % sub, auto), 'Sub']])
        for i, b in enumerate(self.databuf):
            idx = sub + i
            if idx > 7:
                idx -= 8
            if idx == 0:
                if b & 0x80:
                    self._warn(ss, es, 'Control byte 0x%02X has bit 7 set (must be 0)' % b)
                self.put(ss, es, self.out_ann, [ANN_CMD, ['Control 0x%02X: %s' % (b, self._control_label(b)), 'Ctrl']])
            elif idx <= 4:
                self.put(ss, es, self.out_ann, [ANN_DATA, ['Digit %d: %s' % (idx, self._digit_label(b)), 'D%d' % idx]])
            else:
                self._warn(ss, es, 'Data byte for invalid subaddress %d' % idx)

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.sub     = None
            self.databuf = []
            self.is_read = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self._warn(ss, es, 'Unexpected address 0x%02X' % addr)
                self.state = 'IDLE'
                return
            self.addr = addr
            if ptype == 'ADDRESS READ':
                self.is_read = True
                self.state = 'GET_READ'
            else:
                self.state = 'GET_SUB'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_SUB':
                self.sub = pdata
                self.state = 'GET_DATA'
            elif self.state == 'GET_DATA':
                self.databuf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state   = 'IDLE'
            self.sub     = None
            self.databuf = []
            self.is_read = False
