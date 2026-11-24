import sigrokdecode as srd

ADDRS = {0x23, 0x5C}

OP_POWER_DOWN = 0x00
OP_POWER_ON = 0x01
OP_RESET = 0x07
CONTINUOUS = {0x10: 'H-resolution (1 lx)', 0x11: 'H-resolution mode 2 (0.5 lx)', 0x13: 'L-resolution (4 lx)'}
ONE_TIME = {0x20: 'H-resolution (1 lx)', 0x21: 'H-resolution mode 2 (0.5 lx)', 0x23: 'L-resolution (4 lx)'}

# typical measurement time in seconds at MTreg = 69 for each measurement opcode
T_TYP = {0x10: 0.120, 0x11: 0.120, 0x13: 0.016, 0x20: 0.120, 0x21: 0.120, 0x23: 0.016}

DEFAULT_MTREG = 69

ANN_OPCODE  = 0
ANN_RESULT  = 1
ANN_WARNING = 2


def _is_mt_high(code):
    return 0x40 <= code <= 0x47


def _is_mt_low(code):
    return 0x60 <= code <= 0x7F


def _is_half_res(code):
    return code in (0x11, 0x21)


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
    id = 'bh1750'
    name = 'BH1750'
    longname = 'ROHM BH1750FVI ambient light sensor'
    desc = 'Decode BH1750FVI I2C opcodes (power, reset, measurement modes, measurement time) and illuminance results.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['bh1750']
    tags = ['IC', 'Sensor', 'Light']

    annotations = (
        ('opcode',  'Opcode'),
        ('result',  'Illuminance result'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_OPCODE, ANN_RESULT)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann:
            tiered = self.__dict__.get('_tiered')
            if tiered is None:
                tiered = self._tiered = {
                    c for rid, _title, classes in self.annotation_rows
                    if rid not in ('warnings',) for c in classes}
            if data[0] in tiered:
                data = [data[0], _with_short(data[1])]
                self._mirror_python(ss, es)
        super().put(ss, es, out, data)

    def _mirror_python(self, ss, es):
        """OUTPUT_PYTHON mirror of each transaction-level data annotation:
        ('I2C_READ' | 'I2C_WRITE', (address, bytes)) - the chip has no register pointer."""
        out_py = self.__dict__.get('out_python')
        if out_py is None or self.__dict__.get('_py_span') == (ss, es):
            return
        self._py_span = (ss, es)
        try:
            buf = bytes(b & 0xFF for b in getattr(self, 'databuf', None) or ())
        except TypeError:
            return
        rw = 'READ' if getattr(self, 'is_read', False) else 'WRITE'
        super().put(ss, es, out_py, ('I2C_' + rw, (getattr(self, 'addr', None), buf)))

    def __init__(self):
        self.reset()

    def reset(self):
        self.state      = 'IDLE'
        self.addr       = None
        self.is_read    = False
        self.databuf    = []
        self.cmd_buf    = []
        self.ss_block   = None
        self.samplerate = None
        self.mt_high    = None      # last MT[7:5] opcode payload
        self.mt_low     = None      # last MT[4:0] opcode payload
        self.mtreg      = DEFAULT_MTREG
        self.mode_op    = 0x20      # last measurement opcode (decides the lux scaling)
        self.powered    = True      # False after power down or after a one-time measurement
        self.meas_end   = None      # sample number of the end of the last measurement opcode

    def metadata(self, key, value):
        if key == srd.SRD_CONF_SAMPLERATE:
            self.samplerate = value

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _lux(self, raw):
        lux = raw / 1.2 * (DEFAULT_MTREG / self.mtreg)
        return lux / 2.0 if _is_half_res(self.mode_op) else lux

    def _opcode(self, code):
        """Describe one written opcode and update the tracked chip state; returns (text, warning or None)."""
        if code == OP_POWER_DOWN:
            self.powered = False
            return 'Power down', None
        if code == OP_POWER_ON:
            self.powered = True
            return 'Power on', None
        if code == OP_RESET:
            warn = None if self.powered else 'Reset ignored in power-down state (power on first)'
            return 'Reset data register', warn
        if code in CONTINUOUS:
            self.mode_op = code
            self.powered = True
            self.meas_end = self.es
            return 'Continuous %s' % CONTINUOUS[code], None
        if code in ONE_TIME:
            self.mode_op = code
            self.powered = False        # powers down by itself after the measurement
            self.meas_end = self.es
            return 'One-time %s' % ONE_TIME[code], None
        if _is_mt_high(code):
            self.mt_high = code & 0x07
            return 'Measurement time, high bits MT[7:5] = %d' % self.mt_high, None
        if _is_mt_low(code):
            self.mt_low = code & 0x1F
            text = 'Measurement time, low bits MT[4:0] = %d' % self.mt_low
            warn = None
            if self.mt_high is not None:
                self.mtreg = (self.mt_high << 5) | self.mt_low
                text += ' -> MTreg = %d' % self.mtreg
                if not 31 <= self.mtreg <= 254:
                    warn = 'MTreg %d out of range (31-254)' % self.mtreg
                    self.mtreg = DEFAULT_MTREG
            return text, warn
        return None, 'Undocumented opcode 0x%02X' % code

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ'):
            return
        if self.is_read:
            buf = self.databuf
            if not buf:
                return
            if len(buf) != 2:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_RESULT, ['Read %d bytes: %s' % (len(buf), ' '.join('0x%02X' % b for b in buf)), 'R %dB' % len(buf)]])
                self._warn(self.ss_block, self.es, 'Unexpected read length %d (expected 2 bytes)' % len(buf))
                return
            raw = (buf[0] << 8) | buf[1]
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_RESULT, ['Illuminance %.2f lx (raw 0x%04X, MTreg %d)' % (self._lux(raw), raw, self.mtreg),
                                   '%.1f lx' % self._lux(raw)]])
            if self.samplerate and self.meas_end is not None:
                t_min = T_TYP[self.mode_op] * self.mtreg / DEFAULT_MTREG
                elapsed = (self.ss_block - self.meas_end) / self.samplerate
                if elapsed < t_min:
                    self._warn(self.ss_block, self.es,
                               'Read %.1f ms after the measurement opcode, before the typical %.0f ms measurement time'
                               % (elapsed * 1000.0, t_min * 1000.0))
            return
        if not self.cmd_buf:
            return
        code = self.cmd_buf[0]
        text, warn = self._opcode(code)
        if text is not None:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_OPCODE, ['Opcode 0x%02X: %s' % (code, text), 'OP 0x%02X' % code]])
        else:
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_OPCODE, ['Opcode 0x%02X: unknown' % code, 'OP 0x%02X' % code]])
        if warn:
            self._warn(self.ss_block, self.es, warn)
        if len(self.cmd_buf) > 1:
            self._warn(self.ss_block, self.es, 'Unexpected extra bytes after opcode (one opcode per transaction)')

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            self._finish_transaction()
            self.databuf = []
            self.cmd_buf = []
            self.is_read = False
            self.ss_block = ss
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            self.state = 'GET_DATA_READ' if self.is_read else 'GET_DATA_WRITE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_DATA_WRITE':
                self.cmd_buf.append(pdata)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []
            self.cmd_buf = []
