import sigrokdecode as srd

ADDRS = (0x29, 0x39, 0x49)

REGISTERS = {
    0x0: 'CONTROL',
    0x1: 'TIMING',
    0x2: 'THRESHLOWLOW',
    0x3: 'THRESHLOWHIGH',
    0x4: 'THRESHHIGHLOW',
    0x5: 'THRESHHIGHHIGH',
    0x6: 'INTERRUPT',
    0x8: 'CRC',
    0xA: 'ID',
    0xC: 'DATA0LOW',
    0xD: 'DATA0HIGH',
    0xE: 'DATA1LOW',
    0xF: 'DATA1HIGH',
}
WORD_PAIRS = (0x2, 0x4, 0xC, 0xE)
DATA_REGS = (0xC, 0xD, 0xE, 0xF)

INTEG_MS = ('13.7', '101', '402', 'manual')
INTEG_SCALE = (0.034, 0.252, 1.0, 1.0)
INTR_MODE = ('disabled', 'level', 'SMBAlert', 'test')

COEFF = ((0.50, 0.0304, 0.062), (0.61, 0.0224, 0.031), (0.80, 0.0128, 0.0153), (1.30, 0.00146, 0.00112))

ANN_DATA = 0
ANN_WARNING = 1
# Named start/end pair for the conversion_done conformance check (see
# specs/light/tsl2561.md, "Timing Constraints" and "Sigrok Decoder", and
# specs/light/tsl2561_timing.conf).
ANN_CONV_START = 2
ANN_CONV_DONE = 3


def _lux(ch0, ch1, gain, integ):
    """Datasheet lux formula (T/FN/CL coefficients)."""
    factor = (16.0 / gain) / INTEG_SCALE[integ]
    c0 = ch0 * factor
    c1 = ch1 * factor
    if c0 <= 0:
        return 0.0
    r = c1 / c0
    if r <= COEFF[0][0]:
        return max(0.0, COEFF[0][1] * c0 - COEFF[0][2] * c0 * r ** 1.4)
    for lim, a, b in COEFF[1:]:
        if r <= lim:
            return max(0.0, a * c0 - b * c1)
    return 0.0


def _command(cmd):
    return 'CMD=%d CLEAR=%d WORD=%d BLOCK=%d %s' % (
        (cmd >> 7) & 1, (cmd >> 6) & 1, (cmd >> 5) & 1, (cmd >> 4) & 1,
        REGISTERS.get(cmd & 0x0F, 'reserved 0x%X' % (cmd & 0x0F)))


def _with_short(strings):
    """Data annotations carry >= 3 tiers (long, medium, short); if a call site
    supplied fewer, derive the missing tier from the existing text so a narrow
    PulseView zoom still has something to show."""
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
    id = 'tsl2561'
    name = 'TSL2561'
    longname = 'TSL2561 light-to-digital converter (I2C)'
    desc = 'Decode TSL2561 I2C command-byte transactions: power, timing, thresholds, interrupt, ID and ADC data.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['tsl2561']
    tags = ['IC', 'Sensor']

    annotations = (
        ('data',             'Register value'),
        ('warning',          'Warning'),
        ('conversion-start', 'Conversion start'),
        ('conversion-done',  'Conversion done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('timing',   'Timing',   (ANN_CONV_START, ANN_CONV_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann and data[0] == ANN_DATA:
            data = [data[0], _with_short(data[1])]
        super().put(ss, es, out, data)

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'        # IDLE | WAIT_CMD | WRITE | READ
        self.ptr = None            # register address from the last command byte
        self.word = False          # WORD bit of the last command byte
        self.buf = []              # (ss, es, byte) of the current data phase
        self.gain = 1              # from the last TIMING write seen
        self.integ = 2
        self.conv_pending = False  # a power-up was seen, no data read yet

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg, short='WARN'):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, short]])

    def _reg_text(self, reg, val):
        if reg == 0x0:
            state = {3: 'powered up', 0: 'powered down'}.get(val & 3, 'POWER=%d' % (val & 3))
            return 'POWER=%d (%s)' % (val & 3, state)
        if reg == 0x1:
            return 'GAIN=%dx MANUAL=%d INTEG=%s ms' % (16 if val & 0x10 else 1, (val >> 3) & 1, INTEG_MS[val & 3])
        if reg == 0x6:
            return 'INTR=%s PERSIST=%d' % (INTR_MODE[(val >> 4) & 3], val & 0x0F)
        if reg == 0xA:
            part = {0: 'TSL2560', 1: 'TSL2561'}.get(val >> 4, 'unknown')
            return 'PARTNO=%d (%s) REVNO=%d' % (val >> 4, part, val & 0x0F)
        return '0x%02X' % val

    def _emit_bytes(self, rw, start_reg):
        """Annotate the data bytes of one transaction, register by register
        (word pairs and the four ADC bytes are combined)."""
        reg = start_reg
        i = 0
        channels = {}
        while i < len(self.buf):
            ss, es, val = self.buf[i]
            name = REGISTERS.get(reg & 0x0F, 'reserved 0x%X' % (reg & 0x0F))
            if (reg & 0x0F) in WORD_PAIRS and i + 1 < len(self.buf):
                _ss2, es2, val2 = self.buf[i + 1]
                word = val | (val2 << 8)
                base = reg & 0x0F
                if base in (0x2, 0x4):
                    label = 'THRESHLOW' if base == 0x2 else 'THRESHHIGH'
                    self.put(ss, es2, self.out_ann, [ANN_DATA, [
                        '%s %s [0x%X] = %d counts' % (rw, label, base, word), '%s %s %d' % (rw, label, word), label]])
                else:
                    ch = 0 if base == 0xC else 1
                    channels[ch] = (ss, es2, word)
                    self.put(ss, es2, self.out_ann, [ANN_DATA, [
                        '%s CH%d [0x%X] = %d counts' % (rw, ch, base, word), 'CH%d %d' % (ch, word), 'CH%d' % ch]])
                self.put(ss, es2, self.out_python, ('REG_READ' if rw == 'R' else 'REG_WRITE', (base, word)))
                reg += 2
                i += 2
                continue
            if rw == 'W' and (reg & 0x0F) == 0x1:
                self.gain = 16 if val & 0x10 else 1
                self.integ = val & 3
            self.put(ss, es, self.out_ann, [ANN_DATA, [
                '%s %s [0x%X] = 0x%02X: %s' % (rw, name, reg & 0x0F, val, self._reg_text(reg & 0x0F, val)),
                '%s %s 0x%02X' % (rw, name, val), name[:8]]])
            self.put(ss, es, self.out_python, ('REG_READ' if rw == 'R' else 'REG_WRITE', (reg & 0x0F, val)))
            if rw == 'W' and (reg & 0x0F) == 0x0 and (val & 3) == 3:
                self.conv_pending = True
                self.put(ss, es, self.out_ann, [ANN_CONV_START, [
                    'conversion_start: CONTROL write 0x03 (power up)', 'conversion_start', '→CONV']])
            reg += 1
            i += 1
        if 0 in channels and 1 in channels:
            ss = channels[0][0]
            es = channels[1][1]
            lux = _lux(channels[0][2], channels[1][2], self.gain, self.integ)
            self.put(ss, es, self.out_ann, [ANN_DATA, [
                'LUX %.2f lx (CH0 %d, CH1 %d, %dx, %s ms, T/FN/CL)' % (
                    lux, channels[0][2], channels[1][2], self.gain, INTEG_MS[self.integ]),
                'LUX %.1f' % lux, 'LUX']])

    def _maybe_conv_done(self, start_reg):
        if not self.conv_pending or not self.buf:
            return
        if any(((start_reg + k) & 0x0F) in DATA_REGS for k in range(len(self.buf))):
            ss, es, _ = self.buf[0]
            self.put(ss, es, self.out_ann, [ANN_CONV_DONE, [
                'conversion_done: first ADC data register read after power-up', 'conversion_done', 'CONV✓']])
            self.conv_pending = False

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype in ('START', 'START REPEAT'):
            self.state = 'IDLE'
            self.buf = []

        elif ptype == 'ADDRESS WRITE':
            self.state = 'WAIT_CMD' if pdata in ADDRS else 'IGNORE'

        elif ptype == 'ADDRESS READ':
            self.state = 'READ' if pdata in ADDRS else 'IGNORE'
            self.buf = []

        elif ptype == 'DATA WRITE':
            if self.state == 'WAIT_CMD':
                cmd = pdata
                self.put(ss, es, self.out_ann, [ANN_DATA, [
                    'COMMAND 0x%02X: %s' % (cmd, _command(cmd)),
                    'CMD %s' % REGISTERS.get(cmd & 0x0F, '0x%X' % (cmd & 0x0F)), 'CMD']])
                if not cmd & 0x80:
                    self._warn(ss, es, 'Command byte 0x%02X has CMD = 0 (not a command)' % cmd, 'CMD=0')
                if cmd & 0x40:
                    self._warn(ss, es, 'CLEAR = 1: clears a pending interrupt', 'CLEAR')
                self.ptr = cmd & 0x0F
                self.word = bool(cmd & 0x20)
                self.state = 'WRITE'
                self.buf = []
            elif self.state == 'WRITE':
                self.buf.append((ss, es, pdata))

        elif ptype == 'DATA READ':
            if self.state == 'READ':
                self.buf.append((ss, es, pdata))

        elif ptype == 'STOP':
            if self.state == 'WRITE' and self.buf and self.ptr is not None:
                if self.word and self.ptr not in WORD_PAIRS:
                    self._warn(self.buf[0][0], self.buf[-1][1],
                               'Word write to %s, which is not the low half of a 16-bit pair'
                               % REGISTERS.get(self.ptr, '0x%X' % self.ptr), 'WORD?')
                self._emit_bytes('W', self.ptr)
            elif self.state == 'READ' and self.buf and self.ptr is not None:
                self._maybe_conv_done(self.ptr)
                self._emit_bytes('R', self.ptr)
            self.state = 'IDLE'
            self.buf = []
