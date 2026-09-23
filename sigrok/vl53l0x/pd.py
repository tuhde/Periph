import sigrokdecode as srd

# Page-0 register names (ST API VL53L0X_REG_* with the prefix dropped).
REGISTERS = {
    0x00: 'SYSRANGE_START',
    0x01: 'SYSTEM_SEQUENCE_CONFIG',
    0x04: 'SYSTEM_INTERMEASUREMENT_PERIOD',
    0x09: 'SYSTEM_RANGE_CONFIG',
    0x0A: 'SYSTEM_INTERRUPT_CONFIG_GPIO',
    0x0B: 'SYSTEM_INTERRUPT_CLEAR',
    0x0C: 'SYSTEM_THRESH_HIGH',
    0x0E: 'SYSTEM_THRESH_LOW',
    0x13: 'RESULT_INTERRUPT_STATUS',
    0x14: 'RESULT_RANGE_STATUS',
    0x20: 'CROSSTALK_COMPENSATION_PEAK_RATE_MCPS',
    0x28: 'ALGO_PART_TO_PART_RANGE_OFFSET_MM',
    0x30: 'ALGO_PHASECAL_CONFIG_TIMEOUT',
    0x32: 'GLOBAL_CONFIG_VCSEL_WIDTH',
    0x44: 'FINAL_RANGE_CONFIG_MIN_COUNT_RATE_RTN_LIMIT',
    0x46: 'MSRC_CONFIG_TIMEOUT_MACROP',
    0x47: 'FINAL_RANGE_CONFIG_VALID_PHASE_LOW',
    0x48: 'FINAL_RANGE_CONFIG_VALID_PHASE_HIGH',
    0x50: 'PRE_RANGE_CONFIG_VCSEL_PERIOD',
    0x51: 'PRE_RANGE_CONFIG_TIMEOUT_MACROP',
    0x56: 'PRE_RANGE_CONFIG_VALID_PHASE_LOW',
    0x57: 'PRE_RANGE_CONFIG_VALID_PHASE_HIGH',
    0x60: 'MSRC_CONFIG_CONTROL',
    0x61: 'PRE_RANGE_CONFIG_SIGMA_THRESH',
    0x70: 'FINAL_RANGE_CONFIG_VCSEL_PERIOD',
    0x71: 'FINAL_RANGE_CONFIG_TIMEOUT_MACROP',
    0x80: 'POWER_MANAGEMENT_GO1_POWER_FORCE',
    0x84: 'GPIO_HV_MUX_ACTIVE_HIGH',
    0x88: 'I2C_MODE',
    0x89: 'VHV_CONFIG_PAD_SCL_SDA__EXTSUP_HV',
    0x8A: 'I2C_SLAVE_DEVICE_ADDRESS',
    0xB0: 'GLOBAL_CONFIG_SPAD_ENABLES_REF_0..5',
    0xB6: 'GLOBAL_CONFIG_REF_EN_START_SELECT',
    0xC0: 'IDENTIFICATION_MODEL_ID',
    0xC2: 'IDENTIFICATION_REVISION_ID',
    0xF8: 'OSC_CALIBRATE_VAL',
    0xFF: 'PAGE_SELECT',
}

# Multi-byte registers: index -> width in bytes.
WIDTHS = {0x04: 4, 0x0C: 2, 0x0E: 2, 0x20: 2, 0x28: 2, 0x44: 2, 0x51: 2, 0x61: 2, 0x71: 2, 0xB0: 6, 0xF8: 2}

SOURCES = {0: 'disabled', 1: 'level low', 2: 'level high', 3: 'out of window', 4: 'new sample ready'}

RANGE_STATUS = (
    'none', 'VCSEL continuity fail', 'VCSEL watchdog fail', 'no VHV value', 'MSRC no target',
    'SNR check', 'range phase check', 'sigma threshold', 'TCC', 'phase consistency', 'min clip',
    'range complete', 'algo underflow', 'algo overflow', 'range ignore threshold', 'status 15',
)

ANN_DATA = 0
ANN_STATUS = 1
ANN_PRIVATE = 2
ANN_WARNING = 3
# Named start/end pair for the single_ranging conformance check (see
# specs/tof/vl53l0x.md, "Timing Constraints" and "Sigrok Decoder", and
# specs/tof/vl53l0x_timing.conf).
ANN_RANGING_START = 4
ANN_RANGING_DONE = 5


def _vcsel(reg):
    return (reg + 1) << 1


def _timeout(reg16):
    return ((reg16 & 0xFF) << (reg16 >> 8)) + 1


def _sequence(v):
    steps = [name for bit, name in ((0x80, 'FINAL_RANGE'), (0x40, 'PRE_RANGE'), (0x10, 'TCC'),
                                    (0x08, 'DSS'), (0x04, 'MSRC')) if v & bit]
    if v == 0x01:
        return 'VHV calibration'
    if v == 0x02:
        return 'phase calibration'
    return '+'.join(steps) or 'none'


class Decoder(srd.Decoder):
    api_version = 3
    id = 'vl53l0x'
    name = 'VL53L0X'
    longname = 'VL53L0X Time-of-Flight ranging sensor'
    desc = 'Decode VL53L0X I2C register read/write transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['vl53l0x']
    tags = ['IC', 'Sensor']

    options = (
        {'id': 'address', 'desc': 'I2C address (7-bit)', 'default': 0x29},
    )
    annotations = (
        ('data',                  'Register value'),
        ('status',                'Interrupt status / identification'),
        ('private',               'Private-bank sequence'),
        ('warning',               'Warning'),
        ('single-ranging-start',  'Single ranging start'),
        ('single-ranging-done',   'Single ranging done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('private',  'Private',  (ANN_PRIVATE,)),
        ('timing',   'Timing',   (ANN_RANGING_START, ANN_RANGING_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'          # IDLE | GET_PTR | WRITE | READ | IGNORE
        self.ptr = None              # register index, kept across transactions
        self.buf = []                # (ss, es, byte) of the current data phase
        self.direction = None
        self.page = 0                # last value written to 0xFF
        self.power_force = 0         # last value written to 0x80
        self.private = []            # (rw, page, reg, value) of the open private sequence
        self.private_ss = None
        self.private_es = None
        self.sequence_config = None  # last SYSTEM_SEQUENCE_CONFIG written
        self.continuous = False
        self.stop_variable_ok = False
        self.ranging_pending = False
        self.read_start_reg = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _put(self, ss, es, ann, texts):
        self.put(ss, es, self.out_ann, [ann, texts])

    def _warn(self, ss, es, msg, tag):
        self._put(ss, es, ANN_WARNING, [msg, tag])

    # --- Private-bank sequences ------------------------------------------

    def _in_private(self):
        return self.page != 0 or self.power_force != 0

    def _private_access(self, ss, es, rw, reg, value):
        if self.private_ss is None:
            self.private_ss = ss
        self.private_es = es
        self.private.append((rw, self.page, reg, value))

    def _close_private(self):
        if self.private_ss is None:
            return
        acc, ss, es = self.private, self.private_ss, self.private_es
        self.private, self.private_ss, self.private_es = [], None, None
        summary = None
        for rw, page, reg, value in acc:
            if page == 1 and reg == 0x91:
                if rw == 'R':
                    summary = 'stop variable read = 0x%02X' % value
                elif value:
                    summary = 'stop variable ← 0x%02X (ranging preamble)' % value
                    self.stop_variable_ok = True
                else:
                    summary = 'stop variable cleared (stop continuous)'
                    self.stop_variable_ok = False
            elif page == 7 and reg == 0x92 and rw == 'R':
                summary = 'SPAD info: %d reference SPADs, %s' % (
                    value & 0x7F, 'aperture' if value & 0x80 else 'non-aperture')
            elif page == 1 and reg in (0x4E, 0x4F) and summary is None:
                summary = 'dynamic SPAD configuration'
            elif page == 1 and reg == 0x30 and rw == 'W' and summary is None:
                summary = 'PHASECAL_LIM ← 0x%02X' % value
        writes = sum(1 for a in acc if a[0] == 'W')
        if summary is None:
            summary = 'private bank: %d write(s), %d read(s)' % (writes, len(acc) - writes)
        self._put(ss, es, ANN_PRIVATE, [summary, 'private', 'P'])

    # --- Page-0 registers -------------------------------------------------

    def _sysrange_write(self, ss, es, value):
        if value == 0x00:
            text = 'idle'
        elif value & 0x40:
            text = 'VHV calibration start'
        elif value & 0x04:
            text = 'start timed continuous'
        elif value & 0x02:
            text = 'start back-to-back continuous'
        elif self.continuous:
            text = 'stop continuous'
        elif self.sequence_config in (0x01, 0x02):
            text = 'reference calibration start'
        else:
            text = 'start single shot'
        self._put(ss, es, ANN_DATA, ['W SYSRANGE_START [0x%02X] %s' % (value, text), 'SYSRANGE %s' % text, 'RNG'])

        calibration = self.sequence_config in (0x01, 0x02) or value & 0x40
        starting = value & 0x06 or (value == 0x01 and not self.continuous and not calibration)
        if starting:
            if not self.stop_variable_ok:
                self._warn(ss, es, 'Ranging start without a stop-variable (0x91) write since the last start/stop',
                           'SV?')
            self.stop_variable_ok = False
        if value & 0x06:
            self.continuous = True
        elif value == 0x01 and self.continuous:
            self.continuous = False
            self.stop_variable_ok = False
        elif value == 0x01 and not calibration:
            self._put(ss, es, ANN_RANGING_START,
                      ['single_ranging_start: SYSRANGE_START = 0x01', 'single_ranging_start', 'S▶'])
            self.ranging_pending = True

    def _result_block(self, ss, es, data):
        status = (data[0] & 0x78) >> 3
        text = 'range status %d (%s)' % (status, RANGE_STATUS[status])
        if len(data) >= 12:
            distance = (data[10] << 8) | data[11]
            signal = ((data[6] << 8) | data[7]) / 128
            ambient = ((data[8] << 8) | data[9]) / 128
            spads = ((data[2] << 8) | data[3]) / 256
            self._put(ss, es, ANN_DATA,
                      ['R result: %d mm, %s, signal %.2f MCPS, ambient %.2f MCPS, %.1f SPADs'
                       % (distance, text, signal, ambient, spads),
                       '%d mm (%s)' % (distance, RANGE_STATUS[status]), '%d mm' % distance])
        else:
            self._put(ss, es, ANN_DATA, ['R RESULT_RANGE_STATUS: %s' % text, 'status %d' % status, 'RS'])

    def _register(self, ss, es, rw, reg, value, width):
        name = REGISTERS.get(reg, 'reg 0x%02X' % reg)
        raw = ('0x%0' + str(2 * width) + 'X') % value
        if rw == 'W' and reg == 0x00:
            self._sysrange_write(ss, es, value)
            return
        if reg == 0x01:
            if rw == 'W':
                self.sequence_config = value
            text = _sequence(value)
        elif reg == 0x0A:
            text = 'source: %s' % SOURCES.get(value & 0x07, 'reserved')
        elif reg in (0x0C, 0x0E):
            text = '%d mm' % ((value & 0x0FFF) * 2)
        elif reg == 0x13:
            self._put(ss, es, ANN_STATUS,
                      ['R RESULT_INTERRUPT_STATUS [%s] source %d%s' % (raw, value & 0x07,
                                                                       ', range error' if value & 0x18 else ''),
                       'INT %d' % (value & 0x07), 'I'])
            return
        elif reg == 0x0B:
            self._put(ss, es, ANN_STATUS, ['%s SYSTEM_INTERRUPT_CLEAR [%s]' % (rw, raw), 'INT CLR', 'C'])
            return
        elif reg in (0xC0, 0xC2):
            label = 'model ID' if reg == 0xC0 else 'revision ID'
            self._put(ss, es, ANN_STATUS, ['%s %s [%s]' % (rw, name, raw), '%s %s' % (label, raw), 'ID'])
            if reg == 0xC0 and rw == 'R' and value != 0xEE:
                self._warn(ss, es, 'Model ID 0x%02X, expected 0xEE' % value, 'ID?')
            return
        elif reg in (0x50, 0x70):
            text = '%d PCLKs' % _vcsel(value)
        elif reg in (0x51, 0x71):
            text = '%d MCLKs' % _timeout(value)
        elif reg == 0x46:
            text = '%d MCLKs' % (value + 1)
        elif reg == 0x44:
            text = '%.3f MCPS' % (value / 128)
        elif reg == 0x28:
            off = value & 0x0FFF
            if off & 0x0800:
                off -= 0x1000
            text = '%.2f mm' % (off * 0.25)
        elif reg == 0x20:
            text = '%.4f MCPS' % (value / 8192) if value else 'off'
        elif reg == 0x8A:
            text = 'address → 0x%02X' % (value & 0x7F)
        elif reg == 0x04:
            text = '%d osc ticks' % value
        elif reg == 0x89:
            text = '2V8 I/O' if value & 0x01 else '1V8 I/O'
        elif reg == 0x84:
            text = 'GPIO1 active %s' % ('high' if value & 0x10 else 'low')
        else:
            text = ''
        self._put(ss, es, ANN_DATA, ['%s %s [%s] %s' % (rw, name, raw, text), '%s %s %s' % (rw, name, text),
                                     '%s%02X' % (rw, reg)])

    def _access(self, rw, reg, buf):
        """Annotate a burst of bytes starting at register index reg."""
        i = 0
        while i < len(buf):
            r = (reg + i) & 0xFF
            ss, es, value = buf[i]
            if rw == 'W' and r == 0xFF:
                self.page = value
                if self._in_private():
                    self._private_access(ss, es, rw, r, value)
                else:
                    self._private_access(ss, es, rw, r, value)
                    self._close_private()
                i += 1
                continue
            if rw == 'W' and r == 0x80 and self.page == 0:
                self.power_force = value
                self._private_access(ss, es, rw, r, value)
                if not self._in_private():
                    self._close_private()
                i += 1
                continue
            if self._in_private():
                if rw == 'W' and r == 0x00 and value in (0x02, 0x04):
                    self._warn(ss, es, 'Ranging start 0x%02X written while page 0x%02X / power-force 0x%02X is '
                               'selected - lands in the private bank, not SYSRANGE_START'
                               % (value, self.page, self.power_force), 'PAGE?')
                self._private_access(ss, es, rw, r, value)
                i += 1
                continue
            if rw == 'R' and r == 0x14 and i == 0:
                self._result_block(buf[0][0], buf[-1][1], [b[2] for b in buf])
                return
            width = WIDTHS.get(r, 1)
            if i + width > len(buf):
                width = 1
            v = 0
            for b in buf[i:i + width]:
                v = (v << 8) | b[2]
            if r == 0xB0 and width == 6:
                self._put(ss, buf[i + 5][1], ANN_DATA,
                          ['%s reference SPAD map %s' % (rw, ' '.join('%02X' % b[2] for b in buf[i:i + 6])),
                           '%s SPAD map' % rw, 'SPAD'])
            else:
                self._register(ss, buf[i + width - 1][1], rw, r, v, width)
            i += width

    def _flush(self):
        buf, self.buf = self.buf, []
        if not buf or self.ptr is None:
            return
        rw = 'W' if self.direction == 'W' else 'R'
        if rw == 'R':
            self.read_start_reg = None if self._in_private() else self.ptr
        self._access(rw, self.ptr, buf)
        self.ptr = (self.ptr + len(buf)) & 0xFF

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
            self.buf = []
            self.read_start_reg = None

        elif ptype == 'START REPEAT':
            self._flush()
            self.state = 'IDLE'

        elif ptype == 'ADDRESS WRITE':
            if pdata == self.options['address']:
                self.state = 'GET_PTR'
                self.direction = 'W'
            else:
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if pdata == self.options['address']:
                self.state = 'READ'
                self.direction = 'R'
            else:
                self.state = 'IGNORE'

        elif ptype == 'DATA WRITE':
            if self.state == 'GET_PTR':
                self.ptr = pdata
                self.state = 'WRITE'
            elif self.state == 'WRITE':
                self.buf.append((ss, es, pdata))

        elif ptype == 'DATA READ':
            if self.state == 'READ':
                self.buf.append((ss, es, pdata))

        elif ptype == 'STOP':
            was_read = self.state == 'READ'
            self._flush()
            if was_read and self.ranging_pending and self.read_start_reg == 0x14:
                self._put(ss, es, ANN_RANGING_DONE,
                          ['single_ranging_done: result block read', 'single_ranging_done', 'S■'])
                self.ranging_pending = False
            self.state = 'IDLE'
