import sigrokdecode as srd

# Register names (ST ULD names), 16-bit indices.
REGISTERS = {
    0x0001: 'I2C_SLAVE__DEVICE_ADDRESS',
    0x0008: 'VHV_CONFIG__TIMEOUT_MACROP_LOOP_BOUND',
    0x000B: 'VHV_CONFIG__INIT',
    0x0016: 'ALGO__CROSSTALK_COMPENSATION_PLANE_OFFSET_KCPS',
    0x0018: 'ALGO__CROSSTALK_COMPENSATION_X_PLANE_GRADIENT_KCPS',
    0x001A: 'ALGO__CROSSTALK_COMPENSATION_Y_PLANE_GRADIENT_KCPS',
    0x001E: 'ALGO__PART_TO_PART_RANGE_OFFSET_MM',
    0x0020: 'MM_CONFIG__INNER_OFFSET_MM',
    0x0022: 'MM_CONFIG__OUTER_OFFSET_MM',
    0x002E: 'PAD_I2C_HV__EXTSUP_CONFIG',
    0x002F: 'GPIO__EXTSUP_HV',
    0x0030: 'GPIO_HV_MUX__CTRL',
    0x0031: 'GPIO__TIO_HV_STATUS',
    0x0046: 'SYSTEM__INTERRUPT_CONFIG_GPIO',
    0x004B: 'PHASECAL_CONFIG__TIMEOUT_MACROP',
    0x005E: 'RANGE_CONFIG__TIMEOUT_MACROP_A',
    0x0060: 'RANGE_CONFIG__VCSEL_PERIOD_A',
    0x0061: 'RANGE_CONFIG__TIMEOUT_MACROP_B',
    0x0063: 'RANGE_CONFIG__VCSEL_PERIOD_B',
    0x0064: 'RANGE_CONFIG__SIGMA_THRESH',
    0x0066: 'RANGE_CONFIG__MIN_COUNT_RATE_RTN_LIMIT_MCPS',
    0x0069: 'RANGE_CONFIG__VALID_PHASE_HIGH',
    0x006C: 'SYSTEM__INTERMEASUREMENT_PERIOD',
    0x0072: 'SYSTEM__THRESH_HIGH',
    0x0074: 'SYSTEM__THRESH_LOW',
    0x0078: 'SD_CONFIG__WOI_SD0',
    0x007A: 'SD_CONFIG__INITIAL_PHASE_SD0',
    0x007F: 'ROI_CONFIG__USER_ROI_CENTRE_SPAD',
    0x0080: 'ROI_CONFIG__USER_ROI_REQUESTED_GLOBAL_XY_SIZE',
    0x0086: 'SYSTEM__INTERRUPT_CLEAR',
    0x0087: 'SYSTEM__MODE_START',
    0x0089: 'RESULT__RANGE_STATUS',
    0x00DE: 'RESULT__OSC_CALIBRATE_VAL',
    0x00E5: 'FIRMWARE__SYSTEM_STATUS',
    0x010F: 'IDENTIFICATION__MODEL_ID',
    0x0110: 'IDENTIFICATION__MODULE_TYPE',
    0x0111: 'IDENTIFICATION__REVISION_ID',
    0x013E: 'ROI_CONFIG__MODE_ROI_CENTRE_SPAD',
}

# Multi-byte registers: index -> width in bytes.
WIDTHS = {0x0016: 2, 0x0018: 2, 0x001A: 2, 0x001E: 2, 0x0020: 2, 0x0022: 2, 0x005E: 2, 0x0061: 2,
          0x0064: 2, 0x0066: 2, 0x006C: 4, 0x0072: 2, 0x0074: 2, 0x0078: 2, 0x007A: 2, 0x00DE: 2}

CONFIG_FIRST = 0x002D
CONFIG_LAST = 0x0087
CONFIG_LEN = CONFIG_LAST - CONFIG_FIRST + 1

# Timing budget: RANGE_CONFIG__TIMEOUT_MACROP_A value -> (ms, mode).
BUDGETS = {
    0x001D: (15, 'short'), 0x0051: (20, 'short'), 0x00D6: (33, 'short'), 0x01AE: (50, 'short'),
    0x02E1: (100, 'short'), 0x03E1: (200, 'short'), 0x0591: (500, 'short'),
    0x001E: (20, 'long'), 0x0060: (33, 'long'), 0x00AD: (50, 'long'), 0x01CC: (100, 'long'),
    0x02D9: (200, 'long'), 0x048F: (500, 'long'),
}

SOURCES = {0: 'level low', 1: 'level high', 2: 'out of window', 3: 'in window'}

# ULD status_rtn: raw RESULT__RANGE_STATUS (bits 4:0) -> mapped status.
STATUS_MAP = (255, 255, 255, 5, 2, 4, 1, 7, 3, 0, 255, 255, 9, 13, 255, 255,
              255, 255, 10, 6, 255, 255, 11, 12)
STATUS_NAMES = {
    0: 'range valid', 1: 'sigma fail', 2: 'signal fail', 3: 'min range clipped', 4: 'out of bounds',
    5: 'hardware fail', 6: 'no wrap check', 7: 'wrap-around fail', 9: 'crosstalk signal fail',
    10: 'synchronisation', 11: 'merged pulse', 12: 'lack of signal', 13: 'min range fail',
    255: 'no update',
}

ANN_DATA = 0
ANN_STATUS = 1
ANN_CONFIG = 2
ANN_WARNING = 3
# Named start/end pair for the single_ranging conformance check (see
# specs/tof/vl53l1x.md, "Timing Constraints" and "Sigrok Decoder", and
# specs/tof/vl53l1x_timing.conf).
ANN_RANGING_START = 4
ANN_RANGING_DONE = 5


class Decoder(srd.Decoder):
    api_version = 3
    id = 'vl53l1x'
    name = 'VL53L1X'
    longname = 'VL53L1X long-distance Time-of-Flight ranging sensor'
    desc = 'Decode VL53L1X I2C register read/write transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['vl53l1x']
    tags = ['IC', 'Sensor']

    options = (
        {'id': 'address', 'desc': 'I2C address (7-bit)', 'default': 0x29},
    )
    annotations = (
        ('data',                  'Register value'),
        ('status',                'Data ready / interrupt / identification'),
        ('config',                'Default configuration block'),
        ('warning',               'Warning'),
        ('single-ranging-start',  'Single ranging start'),
        ('single-ranging-done',   'Single ranging done'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_DATA,)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('config',   'Config',   (ANN_CONFIG,)),
        ('timing',   'Timing',   (ANN_RANGING_START, ANN_RANGING_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state = 'IDLE'          # IDLE | INDEX | WRITE | READ | IGNORE
        self.ptr = None              # register index, kept across transactions
        self.index_bytes = []        # (ss, es, byte) of the index being assembled
        self.buf = []                # (ss, es, byte) of the current data phase
        self.direction = None
        self.config_count = 0        # bytes of the default-config run seen so far
        self.config_ss = None
        self.config_es = None
        self.cleared = True          # an interrupt clear happened since the last result
        self.model = None
        self.ranging_pending = False
        self.read_start_reg = None

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def _put(self, ss, es, ann, texts):
        self.put(ss, es, self.out_ann, [ann, texts])

    def _warn(self, ss, es, msg, tag):
        self._put(ss, es, ANN_WARNING, [msg, tag])

    # --- Default configuration run ----------------------------------------

    def _config_byte(self, ss, es, reg):
        expected = CONFIG_FIRST + self.config_count
        if reg != expected:
            self._close_config()
            if reg != CONFIG_FIRST:
                return False
        if self.config_ss is None:
            self.config_ss = ss
        self.config_es = es
        self.config_count += 1
        if self.config_count == CONFIG_LEN:
            self._close_config()
        return True

    def _close_config(self):
        if self.config_ss is None:
            return
        n = self.config_count
        self._put(self.config_ss, self.config_es, ANN_CONFIG,
                  ['default config block, %d/%d bytes (0x%04X-0x%04X)' % (n, CONFIG_LEN, CONFIG_FIRST,
                                                                          CONFIG_FIRST + n - 1),
                   'default config %d/%d' % (n, CONFIG_LEN), 'CFG'])
        self.config_ss = self.config_es = None
        self.config_count = 0

    # --- Registers --------------------------------------------------------

    def _mode_start(self, ss, es, value):
        text = {0x00: 'stop', 0x10: 'single shot', 0x40: 'timed continuous'}.get(value, 'reserved')
        self._put(ss, es, ANN_DATA, ['W SYSTEM__MODE_START [0x%02X] %s' % (value, text), 'MODE %s' % text, 'MS'])
        if value in (0x10, 0x40):
            if not self.cleared:
                self._warn(ss, es, 'Ranging start without an interrupt clear since the last result', 'CLR?')
        if value == 0x10:
            self._put(ss, es, ANN_RANGING_START,
                      ['single_ranging_start: SYSTEM__MODE_START = 0x10', 'single_ranging_start', 'S▶'])
            self.ranging_pending = True

    def _result_block(self, ss, es, data):
        raw = data[0] & 0x1F
        status = STATUS_MAP[raw] if raw < len(STATUS_MAP) else 255
        name = STATUS_NAMES.get(status, 'status %d' % status)
        self.cleared = False
        if len(data) >= 17:
            spads = ((data[3] << 8) | data[4]) / 256
            ambient = ((data[7] << 8) | data[8]) / 128
            distance = (data[13] << 8) | data[14]
            signal = ((data[15] << 8) | data[16]) / 128
            self._put(ss, es, ANN_DATA,
                      ['R result: %d mm, %s, signal %.2f MCPS, ambient %.2f MCPS, %.1f SPADs'
                       % (distance, name, signal, ambient, spads),
                       '%d mm (%s)' % (distance, name), '%d mm' % distance])
        else:
            self._put(ss, es, ANN_DATA, ['R RESULT__RANGE_STATUS: %s' % name, 'status %d' % status, 'RS'])

    def _register(self, ss, es, rw, reg, value, width):
        name = REGISTERS.get(reg, 'reg 0x%04X' % reg)
        raw = ('0x%0' + str(2 * width) + 'X') % value
        text = ''
        if rw == 'W' and reg == 0x0087:
            self._mode_start(ss, es, value)
            return
        if reg == 0x0031:
            self._put(ss, es, ANN_STATUS, ['R GPIO__TIO_HV_STATUS [%s] data %s' % (
                raw, 'ready' if (value & 0x01) == 0 else 'not ready'), 'DRDY %d' % (1 - (value & 0x01)), 'D'])
            return
        if reg == 0x0086:
            self.cleared = True
            self._put(ss, es, ANN_STATUS, ['%s SYSTEM__INTERRUPT_CLEAR [%s]' % (rw, raw), 'INT CLR', 'C'])
            return
        if reg == 0x00E5:
            self._put(ss, es, ANN_STATUS, ['R FIRMWARE__SYSTEM_STATUS [%s] %s' % (
                raw, 'booted' if value & 0x01 else 'booting'), 'FW %s' % raw, 'FW'])
            return
        if reg in (0x010F, 0x0110, 0x0111):
            label = {0x010F: 'model ID', 0x0110: 'module type', 0x0111: 'revision ID'}[reg]
            self._put(ss, es, ANN_STATUS, ['%s %s [%s]' % (rw, name, raw), '%s %s' % (label, raw), 'ID'])
            if reg == 0x010F and rw == 'R':
                self.model = value
            elif reg == 0x0110 and rw == 'R' and self.model is not None:
                if (self.model << 8) | value != 0xEACC:
                    self._warn(ss, es, 'Sensor ID 0x%02X%02X, expected 0xEACC' % (self.model, value), 'ID?')
            return
        if reg == 0x0046:
            text = 'source: new sample ready' if value & 0x20 else 'source: %s' % SOURCES[value & 0x03]
        elif reg in (0x0072, 0x0074):
            text = '%d mm' % value
        elif reg == 0x005E:
            ms, mode = BUDGETS.get(value, (None, None))
            text = 'timing budget %d ms (%s mode)' % (ms, mode) if ms else 'timing budget (non-table value)'
        elif reg == 0x004B:
            text = {0x14: 'short distance mode', 0x0A: 'long distance mode'}.get(value, '')
        elif reg in (0x0060, 0x0063):
            text = '%d PCLKs' % ((value + 1) << 1)
        elif reg == 0x006C:
            text = '%d osc ticks' % value
        elif reg == 0x0066:
            text = '%.3f MCPS' % (value / 128)
        elif reg == 0x0064:
            text = '%d mm' % (value >> 2)
        elif reg == 0x0080:
            text = '%dx%d SPADs' % ((value & 0x0F) + 1, (value >> 4) + 1)
        elif reg in (0x007F, 0x013E):
            text = 'SPAD %d' % value
        elif reg == 0x001E:
            off = value & 0x1FFF
            if off & 0x1000:
                off -= 0x2000
            text = '%.2f mm' % (off * 0.25)
        elif reg == 0x0016:
            text = '%.5f MCPS' % (value / 512000) if value else 'off'
        elif reg == 0x0001:
            text = 'address → 0x%02X' % (value & 0x7F)
        elif reg in (0x002E, 0x002F):
            text = '2V8 I/O' if value & 0x01 else '1V8 I/O'
        elif reg == 0x0030:
            text = 'GPIO1 active %s' % ('low' if value & 0x10 else 'high')
            if (value & 0x0F) != 0x01:
                self._warn(ss, es, 'GPIO_HV_MUX__CTRL bits 3:0 = 0x%X, must be 0x1' % (value & 0x0F), 'MUX?')
        elif reg == 0x0008:
            text = {0x09: 'two-bound VHV', 0x81: 'full VHV (temperature update)'}.get(value, '')
        self._put(ss, es, ANN_DATA, ['%s %s [%s] %s' % (rw, name, raw, text), '%s %s %s' % (rw, name, text),
                                     '%s%04X' % (rw, reg)])

    def _access(self, rw, reg, buf):
        """Annotate a burst of bytes starting at register index reg."""
        if rw == 'R' and reg == 0x0089:
            self._result_block(buf[0][0], buf[-1][1], [b[2] for b in buf])
            return
        i = 0
        while i < len(buf):
            r = (reg + i) & 0xFFFF
            ss, es, value = buf[i]
            if rw == 'W' and CONFIG_FIRST <= r <= CONFIG_LAST and len(buf) == 1:
                if self._config_byte(ss, es, r):
                    i += 1
                    continue
            else:
                self._close_config()
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
        if rw == 'R':
            self.read_start_reg = self.ptr
        self._access(rw, self.ptr, buf)
        self.ptr = (self.ptr + len(buf)) & 0xFFFF

    def decode(self, ss, es, data):
        ptype, pdata = data

        if ptype == 'START':
            self.state = 'IDLE'
            self.buf = []
            self.read_start_reg = None

        elif ptype == 'START REPEAT':
            self._check_index()
            self._flush()
            self.state = 'IDLE'

        elif ptype == 'ADDRESS WRITE':
            if pdata == self.options['address']:
                self.state = 'INDEX'
                self.direction = 'W'
                self.index_bytes = []
            else:
                self.state = 'IGNORE'

        elif ptype == 'ADDRESS READ':
            if pdata == self.options['address']:
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
            was_read = self.state == 'READ'
            self._flush()
            if was_read and self.ranging_pending and self.read_start_reg == 0x0089:
                self._put(ss, es, ANN_RANGING_DONE,
                          ['single_ranging_done: result block read', 'single_ranging_done', 'S■'])
                self.ranging_pending = False
            self.state = 'IDLE'

    def _check_index(self):
        """A write phase that ended after one byte used a VL53L0X-style 8-bit index."""
        if self.state == 'INDEX' and len(self.index_bytes) == 1:
            ss, es, value = self.index_bytes[0]
            self._warn(ss, es, 'Single-byte register index 0x%02X - the VL53L1X needs a 16-bit index' % value,
                       'IDX?')
            self.state = 'IGNORE'
