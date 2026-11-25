import sigrokdecode as srd

ADDRS = set(range(0x48, 0x4C))

REG_CONVERSION = 0x00
REG_CONFIG = 0x01
REG_LO_THRESH = 0x02
REG_HI_THRESH = 0x03

REGISTERS = {
    REG_CONVERSION: 'CONVERSION',
    REG_CONFIG: 'CONFIG',
    REG_LO_THRESH: 'LO_THRESH',
    REG_HI_THRESH: 'HI_THRESH',
}
WRITABLE = (REG_CONFIG, REG_LO_THRESH, REG_HI_THRESH)

FULL_SCALE = (6.144, 4.096, 2.048, 1.024, 0.512, 0.256, 0.256, 0.256)
SPS = (128, 250, 490, 920, 1600, 2400, 3300, 3300)
QUEUE = ('1 conversion', '2 conversions', '4 conversions', 'disabled')

CFG_OS = 0x8000

ANN_DATA = 0
ANN_STATUS = 1
ANN_WARNING = 2
# Named start/end pair for the conversion_done conformance check (see
# specs/adc_dac/ads1014.md, "Timing Constraints" and "Sigrok Decoder", and
# specs/adc_dac/ads1014_timing.conf).
ANN_CONV_START = 3
ANN_CONV_DONE = 4


def _s16(raw):
    return raw - 0x10000 if raw & 0x8000 else raw


def _code(raw16):
    """12-bit two's-complement code held in bits 15:4."""
    return _s16(raw16) >> 4


def _config(raw):
    pga = (raw >> 9) & 7
    return ('OS=%d PGA=%d (±%g V) MODE=%s DR=%d SPS COMP_MODE=%s COMP_POL=%s COMP_LAT=%s COMP_QUE=%s'
            % ((raw >> 15) & 1, pga, FULL_SCALE[pga], 'single-shot' if raw & 0x0100 else 'continuous',
               SPS[(raw >> 5) & 7], 'window' if raw & 0x10 else 'traditional',
               'active-high' if raw & 0x08 else 'active-low', 'latching' if raw & 0x04 else 'non-latching',
               QUEUE[raw & 3]))


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
    id = 'ads1014'
    name = 'ADS1014'
    longname = 'ADS1014 12-bit I2C ADC with PGA and comparator'
    desc = 'Decode ADS1014 I2C register read/write transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['ads1014']
    tags = ['IC', 'Analog/digital']

    annotations = (
        ('data',             'Register value'),
        ('status',           'CONFIG / comparator state'),
        ('warning',          'Warning'),
        ('conversion-start', 'Conversion start'),
        ('conversion-done',  'Conversion done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_CONV_START, ANN_CONV_DONE)),
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
        self.state = 'IDLE'         # IDLE | GET_PTR | WRITE | READ | IGNORE
        self.ptr = None             # Address Pointer, kept across transactions
        self.buf = []               # (ss, es, byte) of the current data phase
        self.direction = None
        self.fsr = 2.048            # full scale at the last-seen Config write
        self.lo_raw = None          # last LO_THRESH / HI_THRESH word seen on the bus
        self.hi_raw = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg, tag):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, tag]])

    def _check_ready_mode(self, ss, es):
        if self.hi_raw is not None and self.lo_raw is not None and self.hi_raw & 0x8000 and not self.lo_raw & 0x8000:
            self.put(ss, es, self.out_ann,
                     [ANN_STATUS, ['Conversion-ready mode: HI_THRESH bit 15 = 1, LO_THRESH bit 15 = 0 '
                                   '(ALERT/RDY pulses once per conversion)',
                                   'Conversion-ready mode', 'RDY']])

    def _emit(self, ss, es, rw, reg, value):
        name = REGISTERS[reg]
        if reg == REG_CONFIG:
            self.fsr = FULL_SCALE[(value >> 9) & 7]
            text = _config(value)
            self.put(ss, es, self.out_ann,
                     [ANN_STATUS, ['%s CONFIG [0x%04X] %s' % (rw, value, text),
                                   '%s CONFIG 0x%04X' % (rw, value),
                                   '%s CFG' % rw]])
            self.put(ss, es, self.out_python, ('STATUS', (rw, reg, value)))
            if rw == 'W' and value & CFG_OS:
                self.put(ss, es, self.out_ann,
                         [ANN_CONV_START,
                          ['conversion_start: Config write with OS=1',
                           'conversion_start',
                           '\u2192CONV']])
            return
        code = _code(value)
        volts = code * self.fsr / 2048.0
        if reg == REG_CONVERSION:
            self.put(ss, es, self.out_ann,
                     [ANN_DATA, ['R CONVERSION [0x%04X] %d (%.4f V at \u00b1%g V)' % (value, code, volts, self.fsr),
                                 'CONV %d = %.3f V' % (code, volts), '%d' % code]])
            self.put(ss, es, self.out_python, ('DATA', (rw, reg, volts)))
            self.put(ss, es, self.out_ann,
                     [ANN_CONV_DONE,
                      ['conversion_done: CONVERSION register read (code %d)' % code,
                       'conversion_done', 'CONV\u2713']])
            return
        if reg == REG_LO_THRESH:
            self.lo_raw = value
        else:
            self.hi_raw = value
        self.put(ss, es, self.out_ann,
                 [ANN_DATA, ['%s %s [0x%04X] %d (%.4f V at \u00b1%g V)' % (rw, name, value, code, volts, self.fsr),
                             '%s %s %d' % (rw, name, code), '%s%s' % (rw, name[:2])]])
        self.put(ss, es, self.out_python, ('DATA', (rw, reg, volts)))
        if rw == 'W':
            self._check_ready_mode(ss, es)

    def _flush(self):
        """Annotate the data phase collected since the pointer / read address."""
        buf, self.buf = self.buf, []
        if not buf or self.ptr is None:
            return
        rw = 'W' if self.direction == 'W' else 'R'
        ss, es = buf[0][0], buf[-1][1]
        reg = self.ptr
        if reg not in REGISTERS:
            self._warn(ss, es, '%s of undefined pointer 0x%02X' % ('Write' if rw == 'W' else 'Read', reg), 'PTR?')
            return
        if rw == 'W' and reg not in WRITABLE:
            self._warn(ss, es, 'Write to read-only %s' % REGISTERS[reg], 'RO')
            return
        if len(buf) != 2:
            self._warn(ss, es, '%s of %d byte(s) to %s, expected 2'
                       % ('Write' if rw == 'W' else 'Read', len(buf), REGISTERS[reg]), 'LEN?')
            if len(buf) < 2:
                return
        value = (buf[0][2] << 8) | buf[1][2]
        self._emit(ss, buf[1][1], rw, reg, value)

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
            self.buf = []

        elif ptype == 'START REPEAT':
            # Pointer write, repeated START, read: one register access.
            self._flush()
            self.state = 'IDLE'

        elif ptype == 'ADDRESS WRITE':
            if pdata in ADDRS:
                self.state = 'GET_PTR'
                self.direction = 'W'
            else:
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if pdata in ADDRS:
                self.state = 'READ'
                self.direction = 'R'
            else:
                self.state = 'IGNORE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_PTR':
                if pdata & 0xFC:
                    self._warn(ss, es, 'Address Pointer 0x%02X has reserved bits 7:2 set' % pdata, 'PTR?')
                self.ptr = pdata & 0x03
                self.state = 'WRITE'
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, ['Pointer \u2192 %s (0x%02X)' % (REGISTERS[self.ptr], pdata),
                                     'PTR 0x%02X' % pdata,
                                     'P%02X' % pdata]])
            elif self.state == 'WRITE':
                self.buf.append((ss, es, pdata))

        elif ptype == 'DATA READ':
            if self.state == 'READ':
                self.buf.append((ss, es, pdata))

        elif ptype == 'STOP':
            self._flush()
            self.state = 'IDLE'
