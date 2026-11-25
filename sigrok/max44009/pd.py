import sigrokdecode as srd

REGISTERS = {
    0x00: 'INT_STATUS',
    0x01: 'INT_ENABLE',
    0x02: 'CONFIG',
    0x03: 'LUX_HIGH',
    0x04: 'LUX_LOW',
    0x05: 'UPPER_THRESHOLD',
    0x06: 'LOWER_THRESHOLD',
    0x07: 'THRESHOLD_TIMER',
}
READ_ONLY = (0x00, 0x03, 0x04)

ADDRS = (0x4A, 0x4B)

TIM_MS = ('800', '400', '200', '100', '50', '25', '12.5', '6.25')
LUX_PER_LSB = 0.045

ANN_REG  = 0
ANN_WARN = 1


def _lux(exponent, mantissa):
    return (1 << exponent) * mantissa * LUX_PER_LSB


def _decode_config(v):
    return 'CONT=%d MANUAL=%d CDR=%d TIM=%s ms' % ((v >> 7) & 1, (v >> 6) & 1, (v >> 3) & 1, TIM_MS[v & 7])


def _decode_threshold(v, upper):
    e = v >> 4
    m = ((v & 0x0F) << 4) | (0x0F if upper else 0x00)
    return '%.2f lx' % _lux(e, m) if e < 15 else 'invalid exponent 15'


def _with_short(strings):
    """Data/status annotations carry >= 3 tiers (long, medium, short); derive a
    missing short tier from the existing text so a narrow PulseView zoom still
    has something to show."""
    strings = list(strings)
    if len(strings) >= 3 or not strings:
        return strings

    def lead(text):
        text = text.strip()
        n = 0
        while n < len(text) and (text[n].isalnum() or text[n] in '_→'):
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
    id = 'max44009'
    name = 'MAX44009'
    longname = 'MAX44009 ambient light sensor with ADC (I2C)'
    desc = 'Decode MAX44009 I2C register transactions: lux, configuration, thresholds and interrupt.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['max44009']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg',  'Register'),
        ('warn', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG,)),
        ('warnings', 'Warnings', (ANN_WARN,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann and data[0] == ANN_REG:
            data = [data[0], _with_short(data[1])]
        super().put(ss, es, out, data)

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'
        self.ptr_reg = None        # last register pointer written (used by stand-alone reads)
        self.reg = None
        self.data = []             # (ss, es, value) of the data bytes in this transaction
        self.ss_block = None
        self.high = None           # last LUX_HIGH read: (ss, es, value, stopped)
        self.stop_since_high = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARN, [msg, 'WARN']])

    def _text(self, rw, reg, val):
        name = REGISTERS[reg]
        if reg == 0x00:
            detail = 'INTS=%d' % (val & 1)
        elif reg == 0x01:
            detail = 'INTE=%d' % (val & 1)
        elif reg == 0x02:
            detail = _decode_config(val)
        elif reg == 0x03:
            detail = 'E=%d M[7:4]=0x%X (approx. %s)' % (val >> 4, val & 0x0F,
                       'overrange' if (val >> 4) == 15 else '%.2f lx' % _lux(val >> 4, (val & 0x0F) << 4))
        elif reg == 0x04:
            detail = 'M[3:0]=0x%X' % (val & 0x0F)
        elif reg == 0x05:
            detail = _decode_threshold(val, True)
        elif reg == 0x06:
            detail = _decode_threshold(val, False)
        else:
            detail = '%.1f s' % (val * 0.1)
        return '%s %s [0x%02X] = 0x%02X: %s' % (rw, name, reg, val, detail)

    def _emit(self, ss, es, rw, reg, val):
        self.put(ss, es, self.out_ann, [ANN_REG, [self._text(rw, reg, val), '%s %s' % (rw, REGISTERS[reg])]])
        self.put(ss, es, self.out_python, ('REG_READ' if rw == 'R' else 'REG_WRITE', (reg, val)))

    def _lux_pair(self, hss, hes, high, lss, les, low):
        e = high >> 4
        m = ((high & 0x0F) << 4) | (low & 0x0F)
        if e == 15:
            self._warn(hss, hes, 'Exponent 1111: overrange')
            text = 'LUX E=15 overrange'
        else:
            text = 'LUX E=%d M=%d = %.3f lx' % (e, m, _lux(e, m))
        self.put(hss, les, self.out_ann, [ANN_REG, [text, 'LUX']])

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
            self.ss_block = ss
            self.reg = None
            self.data = []

        elif ptype == 'START REPEAT':
            if self.state == 'WAIT_DATA_W':
                self.ptr_reg = self.reg     # pointer write followed by a repeated-start read
            self.state = 'IDLE'
            self.ss_block = ss
            self.reg = None
            self.data = []

        elif ptype == 'ADDRESS WRITE':
            self.state = 'WAIT_REG' if pdata in ADDRS else 'IDLE'

        elif ptype == 'ADDRESS READ':
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.reg = self.ptr_reg
            self.state = 'WAIT_DATA_R'

        elif ptype == 'DATA WRITE':
            if self.state == 'WAIT_REG':
                if pdata not in REGISTERS:
                    self._warn(ss, es, 'Register address 0x%02X is outside 0x00-0x07' % pdata)
                self.reg = pdata
                self.state = 'WAIT_DATA_W'
            elif self.state in ('WAIT_DATA_W', 'WAIT_STOP_W'):
                self.data.append((ss, es, pdata))
                self.state = 'WAIT_STOP_W'
            else:
                self.state = 'IDLE'

        elif ptype == 'DATA READ':
            if self.state in ('WAIT_DATA_R', 'WAIT_STOP_R'):
                self.data.append((ss, es, pdata))
                self.state = 'WAIT_STOP_R'
            else:
                self.state = 'IDLE'

        elif ptype == 'STOP':
            if self.state == 'WAIT_STOP_W' and self.reg in REGISTERS:
                for bss, bes, val in self.data:
                    if self.reg in READ_ONLY:
                        self._warn(bss, bes, 'Write to read-only register %s' % REGISTERS[self.reg])
                    elif self.reg in (0x05, 0x06) and (val >> 4) != 0 and (val >> 3) & 1 == 0 and (val >> 4) != 15:
                        self._warn(bss, bes, 'Threshold code with exponent != 0 and M7 = 0 (invalid format)')
                    self._emit(bss, bes, 'W', self.reg, val)
                self.ptr_reg = self.reg
            elif self.state == 'WAIT_DATA_W':
                self.ptr_reg = self.reg
            elif self.state == 'WAIT_STOP_R' and self.reg in REGISTERS:
                for bss, bes, val in self.data:
                    self._emit(bss, bes, 'R', self.reg, val)
                    if self.reg == 0x03:
                        self.high = (bss, bes, val)
                        self.stop_since_high = False
                    elif self.reg == 0x04 and self.high is not None:
                        if self.stop_since_high:
                            self._warn(bss, bes, 'STOP between LUX_HIGH and LUX_LOW: the registers may have updated in between')
                        self._lux_pair(self.high[0], self.high[1], self.high[2], bss, bes, val)
                        self.high = None
                if self.reg == 0x03:
                    self.stop_since_high = True
            self.state = 'IDLE'
            self.reg = None
            self.data = []
