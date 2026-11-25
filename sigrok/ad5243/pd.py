import sigrokdecode as srd

ADDR = 0x2F

INSTR_A0 = 0x80
INSTR_SD = 0x40

ANN_DATA    = 0
ANN_WARNING = 1


def _decode_instr(byte):
    return 'CH%d%s' % (2 if byte & INSTR_A0 else 1, ' SD' if byte & INSTR_SD else '')


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
    id = 'ad5243'
    name = 'AD5243'
    longname = 'AD5243 dual 256-position I2C digital potentiometer'
    desc = 'Decode AD5243 I2C instruction/data transactions (RDAC wiper writes and reads).'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ad5243']
    tags = ['IC']

    annotations = (
        ('data',    'Instruction / RDAC'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
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
        self.state = 'IDLE'     # IDLE | GET_INSTR | GET_DATA | READ | IGNORE
        self.instr = None        # last write-mode instruction byte, kept across transactions
        self.ndata = 0
        self.databuf = []
        self.is_read = False
        self.reg_byte = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, 'WARN']])

    def _emit_code(self, ss, es, direction, instr, code):
        self.put(ss, es, self.out_ann,
                 [ANN_DATA, ['%s RDAC %s [0x%02X] code=%d ratio=%.4f' % (direction[0], _decode_instr(instr), code, code, code / 256.0),
                             '%s %s 0x%02X' % (direction[0], _decode_instr(instr), code)]])

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'

        elif ptype == 'START REPEAT':
            self.state = 'IDLE'

        elif ptype == 'ADDRESS WRITE':
            if pdata == ADDR:
                self.state = 'GET_INSTR'
                self.ndata = 0
                self.databuf = []
                self.is_read = False
            else:
                self._warn(ss, es, 'Write to 0x%02X - not an AD5243 (0x2F)' % pdata)
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if pdata == ADDR:
                self.state = 'READ'
                self.ndata = 0
                self.databuf = []
                self.is_read = True
                self.reg_byte = self.instr
            else:
                self.state = 'IGNORE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_INSTR':
                self.instr = pdata
                self.reg_byte = pdata
                self.state = 'GET_DATA'
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, ['Instruction [0x%02X] %s -> select RDAC%d%s' % (
                                         pdata, _decode_instr(pdata), 2 if pdata & INSTR_A0 else 1,
                                         ', shutdown' if pdata & INSTR_SD else ''),
                                     'INSTR 0x%02X' % pdata]])
                if pdata & 0x3F:
                    self._warn(ss, es, 'Instruction bits 5:0 should be 0 (got 0x%02X)' % (pdata & 0x3F))
            elif self.state == 'GET_DATA':
                self.ndata += 1
                self.databuf.append(pdata)
                if self.ndata == 2 and self.instr & INSTR_SD:
                    self._warn(ss, es, 'Repeated data byte after instruction with SD=1')
                self._emit_code(ss, es, 'Write', self.instr, pdata)

        elif ptype == 'DATA READ':
            if self.state == 'READ':
                self.ndata += 1
                self.databuf.append(pdata)
                if self.instr is None:
                    self._warn(ss, es, 'Read before any write instruction - channel unknown')
                else:
                    self._emit_code(ss, es, 'Read', self.instr, pdata)
                if self.ndata > 1:
                    self._warn(ss, es, 'Read of more than 1 byte')

        elif ptype == 'STOP':
            self.state = 'IDLE'
