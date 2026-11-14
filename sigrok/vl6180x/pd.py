import sigrokdecode as srd

# Register names (16-bit indices).
REGISTERS = {
    0x000: 'IDENTIFICATION__MODEL_ID',
    0x001: 'IDENTIFICATION__MODEL_REV_MAJOR',
    0x002: 'IDENTIFICATION__MODEL_REV_MINOR',
    0x003: 'IDENTIFICATION__MODULE_REV_MAJOR',
    0x004: 'IDENTIFICATION__MODULE_REV_MINOR',
    0x006: 'IDENTIFICATION__DATE_HI',
    0x007: 'IDENTIFICATION__DATE_LO',
    0x008: 'IDENTIFICATION__TIME',
    0x010: 'SYSTEM__MODE_GPIO0',
    0x011: 'SYSTEM__MODE_GPIO1',
    0x012: 'SYSTEM__HISTORY_CTRL',
    0x014: 'SYSTEM__INTERRUPT_CONFIG_GPIO',
    0x015: 'SYSTEM__INTERRUPT_CLEAR',
    0x016: 'SYSTEM__FRESH_OUT_OF_RESET',
    0x017: 'SYSTEM__GROUPED_PARAMETER_HOLD',
    0x018: 'SYSRANGE__START',
    0x019: 'SYSRANGE__THRESH_HIGH',
    0x01A: 'SYSRANGE__THRESH_LOW',
    0x01B: 'SYSRANGE__INTERMEASUREMENT_PERIOD',
    0x01C: 'SYSRANGE__MAX_CONVERGENCE_TIME',
    0x01E: 'SYSRANGE__CROSSTALK_COMPENSATION_RATE',
    0x021: 'SYSRANGE__CROSSTALK_VALID_HEIGHT',
    0x022: 'SYSRANGE__EARLY_CONVERGENCE_ESTIMATE',
    0x024: 'SYSRANGE__PART_TO_PART_RANGE_OFFSET',
    0x025: 'SYSRANGE__RANGE_IGNORE_VALID_HEIGHT',
    0x026: 'SYSRANGE__RANGE_IGNORE_THRESHOLD',
    0x02C: 'SYSRANGE__MAX_AMBIENT_LEVEL_MULT',
    0x02D: 'SYSRANGE__RANGE_CHECK_ENABLES',
    0x02E: 'SYSRANGE__VHV_RECALIBRATE',
    0x031: 'SYSRANGE__VHV_REPEAT_RATE',
    0x038: 'SYSALS__START',
    0x03A: 'SYSALS__THRESH_HIGH',
    0x03C: 'SYSALS__THRESH_LOW',
    0x03E: 'SYSALS__INTERMEASUREMENT_PERIOD',
    0x03F: 'SYSALS__ANALOGUE_GAIN',
    0x040: 'SYSALS__INTEGRATION_PERIOD',
    0x04D: 'RESULT__RANGE_STATUS',
    0x04E: 'RESULT__ALS_STATUS',
    0x04F: 'RESULT__INTERRUPT_STATUS_GPIO',
    0x050: 'RESULT__ALS_VAL',
    0x062: 'RESULT__RANGE_VAL',
    0x064: 'RESULT__RANGE_RAW',
    0x066: 'RESULT__RANGE_RETURN_RATE',
    0x068: 'RESULT__RANGE_REFERENCE_RATE',
    0x10A: 'READOUT__AVERAGING_SAMPLE_PERIOD',
    0x119: 'FIRMWARE__BOOTUP',
    0x120: 'FIRMWARE__RESULT_SCALER',
    0x212: 'I2C_SLAVE__DEVICE_ADDRESS',
    0x2A3: 'INTERLEAVED_MODE__ENABLE',
}

# Multi-byte registers: index -> width in bytes (big-endian).
WIDTHS = {0x01E: 2, 0x022: 2, 0x03A: 2, 0x03C: 2, 0x040: 2, 0x050: 2, 0x066: 2, 0x068: 2}

# Actual ALS gain per gain code 0-7.
GAIN = (20.0, 10.32, 5.21, 2.60, 1.72, 1.28, 1.01, 40.0)

RANGE_ERRORS = {
    0: 'no error', 1: 'VCSEL continuity', 2: 'VCSEL watchdog test', 3: 'VCSEL watchdog', 4: 'PLL1 lock',
    5: 'PLL2 lock', 6: 'early convergence estimate', 7: 'max convergence', 8: 'no target ignore',
    11: 'max SNR', 12: 'raw ranging underflow', 13: 'raw ranging overflow', 14: 'ranging underflow',
    15: 'ranging overflow',
}

INT_MODES = {0: 'disabled', 1: 'level low', 2: 'level high', 3: 'out of window', 4: 'new sample ready'}

ANN_DATA = 0
ANN_STATUS = 1
ANN_WARNING = 2


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
    id = 'vl6180x'
    name = 'VL6180X'
    longname = 'VL6180X proximity and ambient-light sensor'
    desc = 'Decode VL6180X I2C register read/write transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['vl6180x']
    tags = ['IC', 'Sensor']

    options = (
        {'id': 'address', 'desc': 'I2C address (7-bit)', 'default': 0x29},
    )
    annotations = (
        ('data',    'Register value'),
        ('status',  'Interrupt / error / identification'),
        ('warning', 'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('status',   'Status',   (ANN_STATUS,)),
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
        self.state = 'IDLE'          # IDLE | INDEX | WRITE | READ | IGNORE
        self.ptr = None              # register index, kept across transactions
        self.index_bytes = []        # (ss, es, byte) of the index being assembled
        self.buf = []                # (ss, es, byte) of the current data phase
        self.direction = None
        self.gain = GAIN[6]          # ALS gain, followed from SYSALS__ANALOGUE_GAIN writes
        self.integration_ms = 100    # followed from SYSALS__INTEGRATION_PERIOD writes
        self.address = None          # address written to I2C_SLAVE__DEVICE_ADDRESS
        self.databuf = None
        self.is_read = False
        self.reg_ptr = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _put(self, ss, es, ann, texts):
        self.put(ss, es, self.out_ann, [ann, texts])

    def _warn(self, ss, es, msg, tag):
        self._put(ss, es, ANN_WARNING, [msg, tag])

    # --- Registers --------------------------------------------------------

    def _register(self, ss, es, rw, reg, value, width):
        name = REGISTERS.get(reg, 'reg 0x%03X' % reg)
        raw = ('0x%0' + str(2 * width) + 'X') % value
        text = ''
        if reg == 0x000:
            self._put(ss, es, ANN_STATUS, ['%s %s [%s] model ID' % (rw, name, raw), 'model ID %s' % raw, 'ID'])
            if rw == 'R' and value != 0xB4:
                self._warn(ss, es, 'Model ID 0x%02X, expected 0xB4' % value, 'ID?')
            return
        if reg in (0x001, 0x002, 0x003, 0x004):
            self._put(ss, es, ANN_STATUS, ['%s %s [%s]' % (rw, name, raw), 'rev %s' % raw, 'REV'])
            return
        if reg == 0x04D:
            code = value >> 4
            self._put(ss, es, ANN_STATUS, ['%s RESULT__RANGE_STATUS [%s] error %d: %s, device %s' % (
                rw, raw, code, RANGE_ERRORS.get(code, 'reserved'), 'ready' if value & 1 else 'busy'),
                'range error %d' % code, 'RE'])
            return
        if reg == 0x04E:
            code = value >> 4
            self._put(ss, es, ANN_STATUS, ['%s RESULT__ALS_STATUS [%s] error %d, device %s' % (
                rw, raw, code, 'ready' if value & 1 else 'busy'), 'ALS error %d' % code, 'AE'])
            return
        if reg == 0x04F:
            self._put(ss, es, ANN_STATUS, ['%s RESULT__INTERRUPT_STATUS_GPIO [%s] range %s, ALS %s, error %d' % (
                rw, raw, INT_MODES.get(value & 7, 'reserved'), INT_MODES.get((value >> 3) & 7, 'reserved'), value >> 6),
                'INT %s' % raw, 'I'])
            return
        if reg == 0x015:
            parts = [n for bit, n in ((1, 'range'), (2, 'ALS'), (4, 'error')) if value & bit]
            self._put(ss, es, ANN_STATUS, ['%s SYSTEM__INTERRUPT_CLEAR [%s] clear %s' % (rw, raw, '+'.join(parts) or 'nothing'),
                                           'INT CLR', 'C'])
            return
        if reg == 0x016:
            text = 'fresh out of reset' if value & 1 else 'initialized'
        elif reg == 0x014:
            text = 'range %s, ALS %s' % (INT_MODES.get(value & 7, 'reserved'), INT_MODES.get((value >> 3) & 7, 'reserved'))
        elif reg == 0x018:
            text = '%s, %s' % ('continuous' if value & 2 else 'single shot', 'start/stop' if value & 1 else 'idle')
        elif reg == 0x038:
            text = '%s, %s' % ('continuous' if value & 2 else 'single shot', 'start/stop' if value & 1 else 'idle')
        elif reg == 0x062:
            text = '%d mm' % value
        elif reg == 0x064:
            text = '%d mm raw' % value
        elif reg in (0x019, 0x01A):
            text = '%d mm' % value
        elif reg in (0x01B, 0x03E):
            text = '%d ms' % ((value + 1) * 10)
        elif reg == 0x01C:
            text = '%d ms' % (value & 0x3F)
        elif reg == 0x01E:
            text = '%.3f Mcps' % (value / 128)
        elif reg == 0x024:
            text = '%d mm' % (value - 256 if value & 0x80 else value)
        elif reg == 0x03F:
            code = value & 7
            if rw == 'W':
                self.gain = GAIN[code]
            text = 'gain %.2f (code %d)' % (GAIN[code], code)
        elif reg == 0x040:
            if rw == 'W':
                self.integration_ms = (value & 0x1FF) + 1
            text = '%d ms' % ((value & 0x1FF) + 1)
        elif reg == 0x050:
            text = '%d counts, %.2f lux' % (value, 0.32 * (value / self.gain) * (100.0 / self.integration_ms))
        elif reg in (0x03A, 0x03C):
            text = '%d counts' % value
        elif reg in (0x066, 0x068):
            text = '%.3f Mcps' % (value / 128)
        elif reg == 0x10A:
            text = '%.1f us' % (value * 64.5)
        elif reg == 0x120:
            text = 'scaler %d' % (value & 0x0F)
        elif reg == 0x212:
            text = 'address -> 0x%02X' % (value & 0x7F)
            if rw == 'W':
                self.address = value & 0x7F
        elif reg == 0x2A3:
            text = 'interleaved %s' % ('on' if value & 1 else 'off')
        elif reg == 0x02E:
            text = 'start recalibration' if value & 1 else 'recalibration idle/done'
        self._put(ss, es, ANN_DATA, ['%s %s [%s] %s' % (rw, name, raw, text), '%s %s %s' % (rw, name, text),
                                     '%s%03X' % (rw, reg)])

    def _access(self, rw, reg, buf):
        """Annotate a burst of bytes starting at register index reg."""
        i = 0
        while i < len(buf):
            r = (reg + i) & 0xFFFF
            ss, es, value = buf[i]
            width = WIDTHS.get(r, 1)
            if i + width > len(buf):
                width = 1
            v = 0
            for b in buf[i:i + width]:
                v = (v << 8) | b[2]
            self._register(ss, buf[i + width - 1][1], rw, r, v, width)
            i += width

    def _flush(self):
        buf, self.buf = self.buf, []
        if not buf or self.ptr is None:
            return
        rw = 'W' if self.direction == 'W' else 'R'
        self.is_read = rw == 'R'
        self.reg_ptr = self.ptr
        self.databuf = [b[2] for b in buf]
        self._access(rw, self.ptr, buf)
        self.ptr = (self.ptr + len(buf)) & 0xFFFF

    def decode(self, ss, es, data):
        ptype, pdata = data
        addr = self.options['address']
        if self.address is not None:
            addr = self.address if pdata == self.address and ptype in ('ADDRESS WRITE', 'ADDRESS READ') else addr

        if ptype == 'START':
            self.state = 'IDLE'
            self.buf = []

        elif ptype == 'START REPEAT':
            self._check_index()
            self._flush()
            self.state = 'IDLE'

        elif ptype == 'ADDRESS WRITE':
            if pdata == self.options['address'] or pdata == self.address:
                self.state = 'INDEX'
                self.direction = 'W'
                self.index_bytes = []
            else:
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if pdata == self.options['address'] or pdata == self.address:
                self.state = 'READ'
                self.direction = 'R'
            else:
                self.state = 'IGNORE'

        elif ptype == 'DATA WRITE':
            if self.state == 'INDEX':
                self.index_bytes.append((ss, es, pdata))
                if len(self.index_bytes) == 2:
                    self.ptr = (self.index_bytes[0][2] << 8) | self.index_bytes[1][2]
                    self.state = 'WRITE'
            elif self.state == 'WRITE':
                self.buf.append((ss, es, pdata))

        elif ptype == 'DATA READ':
            if self.state == 'READ':
                self.buf.append((ss, es, pdata))

        elif ptype == 'STOP':
            self._check_index()
            self._flush()
            self.state = 'IDLE'

    def _check_index(self):
        """A write phase that ended after one byte used an 8-bit index."""
        if self.state == 'INDEX' and len(self.index_bytes) == 1:
            ss, es, value = self.index_bytes[0]
            self._warn(ss, es, 'Single-byte register index 0x%02X - the VL6180X needs a 16-bit index' % value,
                       'IDX?')
            self.state = 'IGNORE'
