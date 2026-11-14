import sigrokdecode as srd

ADDR_MIN = 0x2C
ADDR_MAX = 0x2F

ANN_DATA    = 0
ANN_WARNING = 1

# Quick commands (instruction byte with bit 7 set): C3:C0 -> (name, takes channel).
QUICK = {
    0x0: ('NOP', False),
    0x1: ('Restore EEMEM -> RDAC', True),
    0x2: ('Store RDAC -> EEMEM', True),
    0x3: ('Decrement RDAC 6 dB', True),
    0x4: ('Decrement all RDACs 6 dB', False),
    0x5: ('Decrement RDAC one step', True),
    0x6: ('Decrement all RDACs one step', False),
    0x7: ('Reset (restore EEMEMs to RDACs)', False),
    0x8: ('Increment RDAC 6 dB', True),
    0x9: ('Increment all RDACs 6 dB', False),
    0xA: ('Increment RDAC one step', True),
    0xB: ('Increment all RDACs one step', False),
}


def _channel(a):
    """Channel label for register address bits A1:A0 (RDAC1 = 01, RDAC3 = 11)."""
    return {0x1: 'RDAC1', 0x3: 'RDAC3'}.get(a & 0x3)


def _register_name(instr):
    """Name of the register an (access) instruction byte points at."""
    addr = instr & 0x1F
    if instr & 0x20:
        if addr == 0x01:
            return 'EEMEM1'
        if addr == 0x03:
            return 'EEMEM3'
        if 0x04 <= addr <= 0x0F:
            return 'EEMEM%d' % addr
        return {0x1A: 'TOLERANCE_RDAC1_INT', 0x1B: 'TOLERANCE_RDAC1_DEC',
                0x1E: 'TOLERANCE_RDAC3_INT', 0x1F: 'TOLERANCE_RDAC3_DEC'}.get(addr, 'reg 0x%02X' % addr)
    return {0x01: 'RDAC1', 0x03: 'RDAC3'}.get(addr, 'reg 0x%02X' % addr)


def _decode_quick(instr):
    name, takes_channel = QUICK.get((instr >> 3) & 0xF, (None, False))
    if name is None:
        return 'Reserved quick command'
    if takes_channel:
        ch = _channel(instr)
        return '%s %s' % (name, ch if ch else '(A1:A0 = %d)' % (instr & 0x3))
    return name


def _is_wiper(instr):
    return not (instr & 0x20) and (instr & 0x1F) in (0x01, 0x03)


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
    id = 'ad5252'
    name = 'AD5252'
    longname = 'AD5252 dual 256-position I2C digital potentiometer with EEMEM'
    desc = 'Decode AD5252 I2C transactions: RDAC/EEMEM register access, quick commands and EEMEM ACK polling.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ad5252']
    tags = ['IC']

    annotations = (
        ('data',    'Instruction / data'),
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
        self.instr = None        # last register-access instruction, kept across transactions (read pointer)
        self.ptr = None          # register address bits (with EE flag) of the next data byte
        self.ndata = 0
        self.databuf = []
        self.is_read = False
        self.reg_byte = None
        self.addr = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, 'WARN']])

    def _data_text(self, direction, ptr, value):
        name = _register_name(ptr)
        if _is_wiper(ptr):
            return ['%s %s [0x%02X] code=%d ratio=%.4f' % (direction, name, value, value, value / 256.0),
                    '%s %s 0x%02X' % (direction, name, value)]
        if name.startswith('TOLERANCE'):
            return ['%s %s [0x%02X]' % (direction, name, value), '%s 0x%02X' % (name, value)]
        return ['%s %s [0x%02X]' % (direction, name, value), '%s 0x%02X' % (direction, value)]

    def _next_ptr(self, ptr):
        """Register after ptr in a consecutive transfer: RDAC1 -> RDAC3, otherwise +1."""
        if _is_wiper(ptr) and (ptr & 0x1F) == 0x01:
            return ptr + 2
        return ptr + 1

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START' or ptype == 'START REPEAT':
            self.state = 'IDLE'

        elif ptype == 'ADDRESS WRITE':
            if ADDR_MIN <= pdata <= ADDR_MAX:
                self.addr = pdata
                self.state = 'GET_INSTR'
                self.ndata = 0
                self.databuf = []
                self.is_read = False
            else:
                self._warn(ss, es, 'Write to 0x%02X - not an AD5252 (0x2C-0x2F)' % pdata)
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if ADDR_MIN <= pdata <= ADDR_MAX:
                self.addr = pdata
                self.state = 'READ'
                self.ndata = 0
                self.databuf = []
                self.is_read = True
                self.reg_byte = self.instr
                self.ptr = self.instr
            else:
                self.state = 'IGNORE'

        elif ptype == 'NACK':
            if self.state in ('GET_INSTR', 'READ') and self.ndata == 0:
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, ['NACK - EEMEM write cycle running (ACK polling)', 'NACK']])

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_INSTR':
                self.reg_byte = pdata
                if pdata & 0x80:
                    self.put(ss, es, self.out_ann,
                             [ANN_DATA, ['Quick command [0x%02X] %s' % (pdata, _decode_quick(pdata)),
                                         'QUICK 0x%02X' % pdata]])
                    if pdata & 0x04:
                        self._warn(ss, es, 'Quick command A2 should be 0')
                    self.state = 'GET_DATA'
                    self.ptr = None
                else:
                    self.instr = pdata
                    self.ptr = pdata
                    self.state = 'GET_DATA'
                    self.put(ss, es, self.out_ann,
                             [ANN_DATA, ['Instruction [0x%02X] -> %s' % (pdata, _register_name(pdata)),
                                         'INSTR 0x%02X' % pdata]])
                    if pdata & 0x40:
                        self._warn(ss, es, 'Instruction bit 6 should be 0')
            elif self.state == 'GET_DATA':
                self.ndata += 1
                self.databuf.append(pdata)
                if self.ptr is None:
                    self._warn(ss, es, 'Data byte after a quick command')
                else:
                    self.put(ss, es, self.out_ann, [ANN_DATA, self._data_text('Write', self.ptr, pdata)])
                    self.ptr = self._next_ptr(self.ptr)

        elif ptype == 'DATA READ':
            if self.state == 'READ':
                self.ndata += 1
                self.databuf.append(pdata)
                if self.ptr is None:
                    self._warn(ss, es, 'Read before any register instruction - register unknown')
                else:
                    self.put(ss, es, self.out_ann, [ANN_DATA, self._data_text('Read', self.ptr, pdata)])
                    self.ptr += 1

        elif ptype == 'STOP':
            self.state = 'IDLE'
