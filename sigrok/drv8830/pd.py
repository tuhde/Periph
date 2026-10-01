import sigrokdecode as srd

ADDRS = set(range(0x60, 0x69))

REG_CONTROL = 0x00
REG_FAULT = 0x01
REGISTERS = {REG_CONTROL: 'CONTROL', REG_FAULT: 'FAULT'}

VREF = 1.285
VSET_MIN = 6

FUNCTIONS = {0: 'Standby', 1: 'Forward', 2: 'Reverse', 3: 'Brake'}

ANN_DATA            = 0
ANN_STATUS          = 1
ANN_WARNING         = 2
# Named start/end pair for the register_write conformance check (see
# specs/motor/drv8830.md, "Timing Constraints" and "Sigrok Decoder", and
# specs/motor/drv8830_timing.conf). Additive: does not change the data/
# status/warning annotations PulseView's manual verification depends on.
ANN_WRITE_START     = 3
ANN_WRITE_DONE      = 4


def _decode_control(byte):
    vset = byte >> 2
    in1 = byte & 1
    in2 = (byte >> 1) & 1
    func = FUNCTIONS[byte & 0x03]
    if (byte & 0x03) in (0, 3):
        volts = 'unused in %s' % func
    elif vset < VSET_MIN:
        volts = 'reserved'
    else:
        volts = '%.2f V' % (VREF * vset / 16.0)
    return 'VSET=0x%02X (%s) IN1=%d IN2=%d -> %s' % (vset, volts, in1, in2, func)


def _decode_fault(byte):
    return ('FAULT=%d OCP=%d UVLO=%d OTS=%d ILIMIT=%d'
            % (byte & 1, (byte >> 1) & 1, (byte >> 2) & 1,
               (byte >> 3) & 1, (byte >> 4) & 1))


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
    id = 'drv8830'
    name = 'DRV8830'
    longname = 'DRV8830 low-voltage motor driver with I2C interface'
    desc = 'Decode DRV8830 I2C register read/write transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['drv8830']
    tags = ['IC']

    annotations = (
        ('data',                 'CONTROL register'),
        ('status',               'FAULT register'),
        ('warning',              'Warning'),
        ('register-write-start', 'Register write start'),
        ('register-write-done',  'Register write done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_WRITE_START, ANN_WRITE_DONE)),
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
        self.state = 'IDLE'     # IDLE | GET_REG | GET_DATA | READ | IGNORE
        self.reg = None          # register pointer, kept across transactions
        self.addr_ss = None
        self.addr_es = None
        self.wrote = False
        self.nbytes = 0

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, 'WARN']])

    def _emit(self, ss, es, direction, reg, byte):
        if reg not in REGISTERS:
            self._warn(ss, es, '%s of undefined register 0x%02X' % (direction, reg))
            return
        name = REGISTERS[reg]
        if reg == REG_CONTROL:
            self.put(ss, es, self.out_ann,
                     [ANN_DATA, ['%s %s [0x%02X] %s' % (direction[0], name, byte, _decode_control(byte)),
                                 '%s CONTROL 0x%02X' % (direction[0], byte)]])
            if direction == 'Write' and (byte & 0x03) in (1, 2) and (byte >> 2) < VSET_MIN:
                self._warn(ss, es, 'Reserved VSET code 0x%02X with %s' % (byte >> 2, FUNCTIONS[byte & 0x03]))
        else:
            if direction == 'Write':
                text = 'CLEAR' if byte & 0x80 else 'no CLEAR (0x%02X)' % byte
                self.put(ss, es, self.out_ann,
                         [ANN_STATUS, ['W FAULT [0x%02X] %s' % (byte, text), 'W FAULT 0x%02X' % byte]])
            else:
                self.put(ss, es, self.out_ann,
                         [ANN_STATUS, ['R FAULT [0x%02X] %s' % (byte, _decode_fault(byte)),
                                       'R FAULT 0x%02X' % byte]])

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
            self.wrote = False

        elif ptype == 'START REPEAT':
            self.state = 'IDLE'

        elif ptype == 'ADDRESS WRITE':
            if pdata in ADDRS:
                self.state = 'GET_REG'
                self.addr_ss, self.addr_es = ss, es
                self.nbytes = 0
            else:
                self._warn(ss, es, 'Write to 0x%02X - outside 0x60-0x68, not a DRV8830' % pdata)
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            self.state = 'READ' if pdata in ADDRS else 'IGNORE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_REG':
                self.reg = pdata
                self.state = 'GET_DATA'
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, ['Pointer -> %s (0x%02X)' % (REGISTERS.get(pdata, '?'), pdata),
                                     'PTR 0x%02X' % pdata]])
            elif self.state == 'GET_DATA':
                self.nbytes += 1
                if self.nbytes > 1:
                    self._warn(ss, es, 'Extra data byte - DRV8830 has no auto-increment')
                    return
                self._emit(ss, es, 'Write', self.reg, pdata)
                self.wrote = self.reg in REGISTERS

        elif ptype == 'DATA READ':
            if self.state == 'READ' and self.reg is not None:
                self._emit(ss, es, 'Read', self.reg, pdata)

        elif ptype == 'STOP':
            if self.wrote:
                # register_write: ADDRESS WRITE of a CONTROL/FAULT write
                # transaction through its STOP.
                self.put(self.addr_ss, self.addr_es, self.out_ann,
                         [ANN_WRITE_START, ['register_write_start: ADDRESS WRITE of a CONTROL/FAULT write', 'register_write_start', 'WR\u25b6']])
                self.put(ss, es, self.out_ann,
                         [ANN_WRITE_DONE, ['register_write_done: STOP ended the register write', 'register_write_done', 'WR\u2713']])
            self.state = 'IDLE'
            self.wrote = False
