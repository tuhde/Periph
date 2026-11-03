"""BMA180 sigrok protocol decoder.

Sits on top of the sigrok `i2c` decoder and annotates bus transactions with
the BMA180 register names and decoded field values. Only I²C transactions
are decoded — BMA180 SPI bursts use a command-byte prefix that the
upstream `i2c` decoder cannot see.
"""

import sigrokdecode as srd

ADDRS = {0x40, 0x41}

REGS = {
    0x00: 'CHIP_ID',
    0x01: 'VERSION',
    0x02: 'ACC_X_LSB',
    0x03: 'ACC_X_MSB',
    0x04: 'ACC_Y_LSB',
    0x05: 'ACC_Y_MSB',
    0x06: 'ACC_Z_LSB',
    0x07: 'ACC_Z_MSB',
    0x08: 'TEMP',
    0x09: 'STATUS_REG1',
    0x0A: 'STATUS_REG2',
    0x0B: 'STATUS_REG3',
    0x0C: 'STATUS_REG4',
    0x0D: 'CTRL_REG0',
    0x0E: 'CTRL_REG1',
    0x0F: 'CTRL_REG2',
    0x10: 'RESET',
    0x20: 'BW_TCS',
    0x21: 'CTRL_REG3',
    0x22: 'CTRL_REG4',
    0x23: 'HY',
    0x24: 'SLOPE_TAPSENS',
    0x25: 'HIGH_LOW_INFO',
    0x26: 'LOW_DUR',
    0x27: 'HIGH_DUR',
    0x28: 'TAPSENS_TH',
    0x29: 'LOW_TH',
    0x2A: 'HIGH_TH',
    0x2B: 'SLOPE_TH',
    0x2C: 'CD1',
    0x2D: 'CD2',
    0x2E: 'TCO_X',
    0x2F: 'TCO_Y',
    0x30: 'TCO_Z',
    0x31: 'GAIN_T',
    0x32: 'GAIN_X',
    0x33: 'GAIN_Y',
    0x34: 'GAIN_Z',
    0x35: 'OFFSET_LSB1',
    0x36: 'OFFSET_LSB2',
    0x37: 'OFFSET_T',
    0x38: 'OFFSET_X',
    0x39: 'OFFSET_Y',
    0x3A: 'OFFSET_Z',
}

# OFFSET_LSB1 (0x35) bits 3:1 — range code.
RANGE_BITS = {
    0x00: '±1g', 0x02: '±1.5g', 0x04: '±2g', 0x06: '±3g',
    0x08: '±4g', 0x0A: '±8g', 0x0C: '±16g', 0x0E: 'reserved',
}
# Range -> LSB/g scale.
RANGE_SCALE = {
    0x00: 8192, 0x02: 5460, 0x04: 4096, 0x06: 2730,
    0x08: 2048, 0x0A: 1024, 0x0C: 512,
}
# BW_TCS (0x20) bits 7:4.
BW_BITS = {
    0x00: 10.0, 0x10: 20.0, 0x20: 40.0, 0x30: 75.0,
    0x40: 150.0, 0x50: 300.0, 0x60: 600.0, 0x70: 1200.0,
    0x80: 'high-pass 1 Hz', 0x90: 'band-pass 0.2..300 Hz',
}
# TCO_Z (0x30) bits 1:0 — mode_config.
MODE_BITS = {
    0x00: 'low-noise', 0x01: 'ultra-low-noise',
    0x02: 'low-noise/reduced-power', 0x03: 'low-power',
}
# TCO_Y (0x2F) bits 1:0 — wake_up_dur (ms).
WAKE_UP_DUR_MS = {0x00: 20, 0x01: 80, 0x02: 320, 0x03: 2560}
# TCO_X (0x2E) bits 1:0 — slope_dur (consecutive samples).
SLOPE_DUR = {0x00: 1, 0x01: 3, 0x02: 5, 0x03: 7}
# GAIN_T (0x31) bits 2:0 — tapsens_dur (ms).
TAP_DUR_MS = {0x00: 50, 0x01: 75, 0x02: 100, 0x03: 150,
              0x04: 250, 0x05: 500, 0x06: 750, 0x07: 1000}

# CTRL_REG0 (0x0D) bits.
CTRL_REG0_FIELDS = [
    (0x40, 'reset_INT'),
    (0x20, 'update_image'),
    (0x10, 'ee_w'),
    (0x04, 'st0'),
    (0x02, 'sleep'),
]

# STATUS_REG3 (0x0B) — latched source flags.
STATUS_REG3_FIELDS = [
    (0x80, 'HIGH_G_latched'),
    (0x40, 'LOW_G_latched'),
    (0x20, 'SLOPE_latched'),
    (0x10, 'TAP_latched'),
    (0x04, 'X_first'),
    (0x02, 'Y_first'),
    (0x01, 'Z_first'),
]

# STATUS_REG2 (0x1A) bits.
STATUS_REG2_FIELDS = [
    (0x80, 'high_th_s'),
    (0x40, 'low_th_s'),
    (0x20, 'slope_s'),
    (0x10, 'tapsens_s'),
    (0x04, 'low_sign_x'),
    (0x02, 'low_sign_y'),
    (0x01, 'low_sign_z'),
]

# CTRL_REG3 (0x21) bit fields.
CR3_FIELDS = [
    (0x80, 'slope_alert'),
    (0x40, 'slope_int'),
    (0x20, 'high_int'),
    (0x10, 'low_int'),
    (0x08, 'tap_int'),
    (0x04, 'adv_int'),
    (0x02, 'new_data_int'),
    (0x01, 'lat_int'),
]

# CTRL_REG4 (0x22) bit fields.
CR4_FIELDS_OFFSET_LOW_HY  = 0xC0   // bits 7:6
CR4_FIELDS_MOT_CD_R       = 0x30   // bits 5:4
CR4_FIELDS_FF_CD_R        = 0x0C   // bits 3:2
CR4_FIELDS_OFFSET_FINE    = 0x03   // bits 1:0

# HIGH_LOW_INFO (0x25) bit fields.
HLI_HIGH_AXIS = 0xE0  // bits 7:5
HLI_HIGH_FILT = 0x10  // bit 4
HLI_LOW_AXIS  = 0x0E  // bits 3:1
HLI_LOW_FILT   = 0x01  // bit 0

# SLOPE_TAPSENS_INFO (0x24) bit fields.
STI_SLOPE_AXIS = 0xE0  // bits 7:5
STI_SLOPE_FILT = 0x10  // bit 4
STI_TAP_AXIS   = 0x0E  // bits 3:1
STI_TAP_FILT   = 0x01  // bit 0

ANN_WRITE       = 0
ANN_READ        = 1
ANN_WARNING     = 2
ANN_WAKE_START  = 3
ANN_WAKE_DONE   = 4


def _signed14(lo, hi):
    """Decode big-endian signed 14-bit value from ACC_X_LSB/MSB."""
    v = (hi << 6) | (lo >> 2)
    if v >= 0x2000:
        v -= 0x4000
    return v


def _decode_chip_id(raw):
    val = raw & 0x07
    ok = ' (BMA180 OK)' if val == 0x03 else ' (expected 0x03!)'
    return 'CHIP_ID 0x%02X%s' % (raw, ok)


def _decode_bw_tcs(raw):
    bw_code = raw & 0xF0
    bw = BW_BITS.get(bw_code, 'reserved')
    if isinstance(bw, float):
        bw_str = '%.0f Hz LP' % bw
    else:
        bw_str = str(bw)
    return 'BW_TCS 0x%02X: %s' % (raw, bw_str)


def _decode_offset_lsb1(raw, range_code):
    range_str = RANGE_BITS.get(range_code, '?')
    smp = 'smp_skip' if raw & 0x01 else 'no_skip'
    return 'OFFSET_LSB1 0x%02X: range=%s, %s' % (raw, range_str, smp)


def _decode_temperature(raw):
    signed = raw if raw < 128 else raw - 256
    return 'TEMP 0x%02X (%.1f C)' % (raw, 25.0 + (signed - 2) * 0.5)


def _decode_ctrl_reg0(raw):
    parts = []
    for bit, name in CTRL_REG0_FIELDS:
        if raw & bit:
            parts.append(name)
    return 'CTRL_REG0 0x%02X: %s' % (raw, ', '.join(parts) if parts else 'awake')


def _decode_status_reg3(raw):
    parts = []
    for bit, name in STATUS_REG3_FIELDS:
        if raw & bit:
            parts.append(name)
    return 'STATUS_REG3 0x%02X: %s' % (raw, ', '.join(parts) if parts else 'none')


def _decode_status_reg2(raw):
    parts = []
    for bit, name in STATUS_REG2_FIELDS:
        if raw & bit:
            parts.append(name)
    return 'STATUS_REG2 0x%02X: %s' % (raw, ', '.join(parts) if parts else 'none')


def _decode_ctrl_reg3(raw):
    parts = []
    for bit, name in CR3_FIELDS:
        if raw & bit:
            parts.append(name)
    return 'CTRL_REG3 0x%02X: %s' % (raw, ', '.join(parts) if parts else 'none')


def _decode_tco_z(raw):
    code = raw & 0x03
    return 'TCO_Z 0x%02X: mode=%s' % (raw, MODE_BITS.get(code, '?'))


def _decode_tco_y(raw):
    code = raw & 0x03
    wake_dur = WAKE_UP_DUR_MS.get(code, '?')
    return 'TCO_Y 0x%02X: wake_up_dur=%d ms' % (raw, wake_dur)


def _decode_tco_x(raw):
    code = raw & 0x03
    slope_dur = SLOPE_DUR.get(code, '?')
    return 'TCO_X 0x%02X: slope_dur=%d samples' % (raw, slope_dur)


def _decode_gain_t(raw):
    code = raw & 0x07
    tap_dur = TAP_DUR_MS.get(code, '?')
    return 'GAIN_T 0x%02X: tapsens_dur=%d ms' % (raw, tap_dur)


def _decode_high_low_info(raw):
    high_axis = (raw & HLI_HIGH_AXIS) >> 5
    high_filt = 'high_filt' if raw & HLI_HIGH_FILT else 'raw_data'
    low_axis = (raw & HLI_LOW_AXIS) >> 1
    low_filt = 'low_filt' if raw & HLI_LOW_FILT else 'raw_data'
    return 'HIGH_LOW_INFO 0x%02X: high_axes=0x%X(%s), low_axes=0x%X(%s)' % (
        raw, high_axis, high_filt, low_axis, low_filt)


def _decode_slope_tapsens(raw):
    slope_axis = (raw & STI_SLOPE_AXIS) >> 5
    slope_filt = 'slope_filt' if raw & STI_SLOPE_FILT else 'raw_data'
    tap_axis = (raw & STI_TAP_AXIS) >> 1
    tap_filt = 'tap_filt' if raw & STI_TAP_FILT else 'raw_data'
    return 'SLOPE_TAPSENS 0x%02X: slope_axes=0x%X(%s), tap_axes=0x%X(%s)' % (
        raw, slope_axis, slope_filt, tap_axis, tap_filt)


def _decode_axis(lo, hi, range_code):
    raw = _signed14(lo, hi)
    scale = RANGE_SCALE.get(range_code, 4096)
    g = raw / scale
    return 'raw=%+d (%+.4f g)' % (raw, g)


def _decode_sensor_burst(buf, range_code):
    if len(buf) < 6:
        return 'Short burst %dB' % len(buf)
    ax = _decode_axis(buf[0], buf[1], range_code)
    ay = _decode_axis(buf[2], buf[3], range_code)
    az = _decode_axis(buf[4], buf[5], range_code)
    return 'Burst: X=%s, Y=%s, Z=%s' % (ax, ay, az)


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
    id = 'bma180'
    name = 'BMA180'
    longname = 'BMA180 3-axis MEMS accelerometer (Bosch Sensortec)'
    desc = 'Decode BMA180 I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['bma180']
    tags = ['IC', 'Sensor', 'Accelerometer']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('warning',   'Warning'),
        ('wake-start', 'Wake-up: CTRL_REG0 sleep bit cleared'),
        ('wake-done',  'Wake-up: first data-register read'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_WRITE, ANN_READ)),
        ('timing', 'Timing', (ANN_WAKE_START, ANN_WAKE_DONE)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def put(self, ss, es, out, data):
        if out == self.out_ann:
            tiered = self.__dict__.get('_tiered')
            if tiered is None:
                tiered = self._tiered = {
                    c for rid, _title, classes in self.annotation_rows
                    if rid not in ('timing', 'warnings') for c in classes
                }
            if data[0] in tiered:
                data = [data[0], _with_short(data[1])]
                self._mirror_python(ss, es)
        super().put(ss, es, out, data)

    def _mirror_python(self, ss, es):
        """OUTPUT_PYTHON mirror of each transaction-level data annotation."""
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
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None
        self.databuf  = []
        self.ss_block = None
        self.range_code = 0x04  # last seen range bits in OFFSET_LSB1 (default ±2g)

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return

        reg  = self.reg_ptr
        name = REGS.get(reg, 'Reg[0x%02X]' % reg) if reg is not None else '?'

        if self.is_read:
            if reg == 0x00 and len(self.databuf) == 1:
                val = self.databuf[0]
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          [_decode_chip_id(val),
                           'ID 0x%02X' % val]])
            elif reg == 0x02 and len(self.databuf) == 6:
                # wake-done: first data-register read after CTRL_REG0 sleep cleared.
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WAKE_DONE, ['wake_done: first data-register read after sleep was cleared', 'wake_done', 'WAKE\u2713']])
                desc = _decode_sensor_burst(self.databuf, self.range_code)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'Burst 6B']])
            elif reg == 0x08 and len(self.databuf) == 1:
                desc = _decode_temperature(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'T 0x%02X' % self.databuf[0]]])
            elif reg == 0x0B and len(self.databuf) == 1:
                desc = _decode_status_reg3(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'S3 0x%02X' % self.databuf[0]]])
            elif reg == 0x0A and len(self.databuf) == 1:
                desc = _decode_status_reg2(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'S2 0x%02X' % self.databuf[0]]])
            elif reg == 0x0D and len(self.databuf) == 1:
                desc = _decode_ctrl_reg0(self.databuf[0])
                if not (self.databuf[0] & 0x02):  # sleep bit cleared
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_WAKE_START, ['wake_start: CTRL_REG0 sleep bit cleared', 'wake_start', 'WAKE\u25b6']])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'C0 0x%02X' % self.databuf[0]]])
            elif reg == 0x10 and len(self.databuf) == 1:
                val = self.databuf[0]
                if val == 0xB6:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_READ,
                              ['RESET 0xB6: soft_reset issued', 'RESET 0xB6', 'SR 0xB6']])
                else:
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_READ,
                              ['RESET 0x%02X' % val, 'RST 0x%02X' % val]])
            elif reg == 0x20 and len(self.databuf) == 1:
                desc = _decode_bw_tcs(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'BW 0x%02X' % self.databuf[0]]])
            elif reg == 0x21 and len(self.databuf) == 1:
                desc = _decode_ctrl_reg3(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'C3 0x%02X' % self.databuf[0]]])
            elif reg == 0x24 and len(self.databuf) == 1:
                desc = _decode_slope_tapsens(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'ST 0x%02X' % self.databuf[0]]])
            elif reg == 0x25 and len(self.databuf) == 1:
                desc = _decode_high_low_info(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'HLI 0x%02X' % self.databuf[0]]])
            elif reg == 0x30 and len(self.databuf) == 1:
                desc = _decode_tco_z(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'TZ 0x%02X' % self.databuf[0]]])
            elif reg == 0x2F and len(self.databuf) == 1:
                desc = _decode_tco_y(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'TY 0x%02X' % self.databuf[0]]])
            elif reg == 0x2E and len(self.databuf) == 1:
                desc = _decode_tco_x(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'TX 0x%02X' % self.databuf[0]]])
            elif reg == 0x31 and len(self.databuf) == 1:
                desc = _decode_gain_t(self.databuf[0])
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'GT 0x%02X' % self.databuf[0]]])
            elif reg == 0x35 and len(self.databuf) == 1:
                self.range_code = self.databuf[0] & 0x0E
                desc = _decode_offset_lsb1(self.databuf[0], self.range_code)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ, [desc, 'OL1 0x%02X' % self.databuf[0]]])
            elif len(self.databuf) == 1:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['Read %s: 0x%02X' % (name, self.databuf[0]),
                           'R %s 0x%02X' % (name, self.databuf[0])]])
            elif self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['Read %s: %d bytes' % (name, len(self.databuf)),
                           'R %s %dB' % (name, len(self.databuf))]])
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE,
                          ['Pointer \u2192 %s (0x%02X)' % (name, reg),
                           'PTR 0x%02X' % reg]])
            elif len(self.databuf) == 1:
                val = self.databuf[0]
                if reg == 0x0D:
                    desc = _decode_ctrl_reg0(val)
                elif reg == 0x20:
                    self.range_code = val & 0x0E
                    desc = _decode_bw_tcs(val)
                elif reg == 0x21:
                    desc = _decode_ctrl_reg3(val)
                elif reg == 0x22:
                    desc = _decode_ctrl_reg3(val)  # CR4 fields; same decoder ok
                elif reg == 0x24:
                    desc = _decode_slope_tapsens(val)
                elif reg == 0x25:
                    desc = _decode_high_low_info(val)
                elif reg == 0x2E:
                    desc = _decode_tco_x(val)
                elif reg == 0x2F:
                    desc = _decode_tco_y(val)
                elif reg == 0x30:
                    desc = _decode_tco_z(val)
                elif reg == 0x31:
                    desc = _decode_gain_t(val)
                elif reg == 0x35:
                    self.range_code = val & 0x0E
                    desc = _decode_offset_lsb1(val, self.range_code)
                else:
                    desc = 'Write %s: 0x%02X' % (name, val)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE,
                          [desc,
                           'W %s 0x%02X' % (name, val)]])
            elif self.databuf:
                self._warn(self.ss_block, self.es,
                           'Unexpected write length %d for %s' % (len(self.databuf), name))

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass
            else:
                self._finish_transaction()
                self.databuf  = []
                self.is_read  = False
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr    = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.databuf = []
                self.state   = 'GET_DATA_READ'
            else:
                self.state = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            byte = pdata
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = byte
                self.databuf = []
                self.state   = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(byte)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state   = 'IDLE'
            self.databuf = []