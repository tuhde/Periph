import sigrokdecode as srd

ADDRS = set(range(0x48, 0x4C))
ALERT_RESPONSE_ADDR = 0x0C
GENERAL_CALL_ADDR = 0x00

REG_TEMPERATURE = 0x00
REG_CONFIG = 0x01
REG_T_LOW = 0x02
REG_T_HIGH = 0x03

REGISTERS = {
    REG_TEMPERATURE: 'TEMPERATURE',
    REG_CONFIG: 'CONFIGURATION',
    REG_T_LOW: 'T_LOW',
    REG_T_HIGH: 'T_HIGH',
}
TEMPERATURE_REGS = (REG_TEMPERATURE, REG_T_LOW, REG_T_HIGH)

FAULTS = ('1', '2', '4', '6')
RATES = ('0.25 Hz', '1 Hz', '4 Hz', '8 Hz')

CFG_OS = 0x8000

ANN_DATA = 0
ANN_STATUS = 1
ANN_WARNING = 2
# Named start/end pair for the conversion conformance check (see
# specs/temperature/tmp102.md, "Timing Constraints" and "Sigrok Decoder",
# and specs/temperature/tmp102_timing.conf).
ANN_CONV_START = 3
ANN_CONV_DONE = 4


def _temperature(raw):
    """12-bit, or 13-bit when bit 0 is set (extended mode)."""
    if raw & 1:
        value = raw >> 3
        if value & 0x1000:
            value -= 0x2000
    else:
        value = raw >> 4
        if value & 0x800:
            value -= 0x1000
    return value * 0.0625


def _config(raw):
    return ('OS=%d F=%s POL=%s TM=%s SD=%d CR=%s AL=%d EM=%d'
            % ((raw >> 15) & 1, FAULTS[(raw >> 11) & 3],
               'active-high' if raw & 0x0400 else 'active-low',
               'interrupt' if raw & 0x0200 else 'comparator',
               (raw >> 8) & 1, RATES[(raw >> 6) & 3], (raw >> 5) & 1, (raw >> 4) & 1))


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
    id = 'tmp102'
    name = 'TMP102'
    longname = 'TMP102 12-bit digital temperature sensor'
    desc = 'Decode TMP102 I2C register read/write transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['tmp102']
    tags = ['IC', 'Sensor']

    annotations = (
        ('data',             'Register value'),
        ('status',           'CONFIGURATION'),
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
        self.state = 'IDLE'         # IDLE | GET_PTR | WRITE | READ | IGNORE | ALERT | GENERAL
        self.ptr = None             # Register Pointer, kept across transactions
        self.buf = []               # (ss, es, byte) of the current data phase
        self.direction = None
        self.conv_pending = False   # a one-shot write awaits OS = 1

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg, tag):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, tag]])

    def _emit(self, ss, es, rw, reg, value):
        name = REGISTERS[reg]
        if reg == REG_CONFIG:
            self.put(ss, es, self.out_ann,
                     [ANN_STATUS, ['%s CONFIGURATION [0x%04X] %s' % (rw, value, _config(value)),
                                   '%s CONFIG 0x%04X' % (rw, value),
                                   '%s CFG' % rw]])
            self.put(ss, es, self.out_python, ('STATUS', (rw, reg, value)))
            if rw == 'W' and value & CFG_OS:
                self.put(ss, es, self.out_ann,
                         [ANN_CONV_START,
                          ['conversion_start: CONFIGURATION written with OS=1', 'conversion_start', '→CV']])
                self.conv_pending = True
            elif rw == 'R' and self.conv_pending and value & CFG_OS:
                self.put(ss, es, self.out_ann,
                         [ANN_CONV_DONE,
                          ['conversion_done: CONFIGURATION read back OS=1', 'conversion_done', 'CV✓']])
                self.conv_pending = False
            return
        c = _temperature(value)
        self.put(ss, es, self.out_ann,
                 [ANN_DATA, ['%s %s [0x%04X] %.4f °C' % (rw, name, value, c),
                             '%s %s %.2f °C' % (rw, name, c),
                             '%.1f°' % c]])
        self.put(ss, es, self.out_python, ('DATA', (rw, reg, c)))

    def _flush(self):
        """Annotate the data phase collected since the pointer / read address."""
        buf, self.buf = self.buf, []
        if not buf or self.ptr is None:
            return
        rw = 'W' if self.direction == 'W' else 'R'
        ss, es = buf[0][0], buf[-1][1]
        reg = self.ptr & 0x03
        if rw == 'W' and reg == REG_TEMPERATURE:
            self._warn(ss, es, 'Write to read-only TEMPERATURE', 'RO')
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
            elif pdata == GENERAL_CALL_ADDR:
                self.state = 'GENERAL'
            else:
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if pdata in ADDRS:
                self.state = 'READ'
                self.direction = 'R'
            elif pdata == ALERT_RESPONSE_ADDR:
                self.state = 'ALERT'
            else:
                self.state = 'IGNORE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_PTR':
                self.ptr = pdata
                self.state = 'WRITE'
                if pdata & 0xFC:
                    self._warn(ss, es, 'Pointer 0x%02X has bits 7:2 set (must be 0)' % pdata, 'PTR?')
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, ['Pointer → %s (0x%02X)' % (REGISTERS.get(pdata & 0x03, 'undefined'), pdata),
                                     'PTR 0x%02X' % pdata,
                                     'P%02X' % pdata]])
            elif self.state == 'WRITE':
                self.buf.append((ss, es, pdata))
            elif self.state == 'GENERAL':
                msg = 'General-call reset' if pdata == 0x06 else 'General call 0x%02X' % pdata
                self.put(ss, es, self.out_ann, [ANN_DATA, [msg, 'GC 0x%02X' % pdata, 'GC']])

        elif ptype == 'DATA READ':
            if self.state == 'READ':
                self.buf.append((ss, es, pdata))
            elif self.state == 'ALERT':
                self.put(ss, es, self.out_ann,
                         [ANN_DATA, ['SMBus alert response: device 0x%02X' % (pdata >> 1),
                                     'ARA 0x%02X' % (pdata >> 1), 'ARA']])

        elif ptype == 'STOP':
            self._flush()
            self.state = 'IDLE'
