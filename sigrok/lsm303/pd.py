import sigrokdecode as srd

# The LSM303 has two independent I²C slaves on the same bus: the
# accelerometer at 0x19 and the magnetometer at 0x1E.
ADDR_ACCEL = 0x19
ADDR_MAG = 0x1E
ADDRS = {ADDR_ACCEL, ADDR_MAG}

# --- Accelerometer register map (slave 0x19) --------------------------------
ACCEL_REGS = {
    0x20: 'CTRL_REG1_A',
    0x21: 'CTRL_REG2_A',
    0x22: 'CTRL_REG3_A',
    0x23: 'CTRL_REG4_A',
    0x24: 'CTRL_REG5_A',
    0x25: 'CTRL_REG6_A',
    0x26: 'REFERENCE_A',
    0x27: 'STATUS_REG_A',
    0x28: 'OUT_X_L_A',
    0x29: 'OUT_X_H_A',
    0x2A: 'OUT_Y_L_A',
    0x2B: 'OUT_Y_H_A',
    0x2C: 'OUT_Z_L_A',
    0x2D: 'OUT_Z_H_A',
    0x2E: 'FIFO_CTRL_REG_A',
    0x2F: 'FIFO_SRC_REG_A',
    0x30: 'INT1_CFG_A',
    0x31: 'INT1_SOURCE_A',
    0x32: 'INT1_THS_A',
    0x33: 'INT1_DURATION_A',
    0x34: 'INT2_CFG_A',
    0x35: 'INT2_SOURCE_A',
    0x36: 'INT2_THS_A',
    0x37: 'INT2_DURATION_A',
    0x38: 'CLICK_CFG_A',
    0x39: 'CLICK_SRC_A',
    0x3A: 'CLICK_THS_A',
    0x3B: 'TIME_LIMIT_A',
    0x3C: 'TIME_LATENCY_A',
    0x3D: 'TIME_WINDOW_A',
}

# --- Magnetometer register map (slave 0x1E) ---------------------------------
MAG_REGS = {
    0x00: 'CRA_REG_M',
    0x01: 'CRB_REG_M',
    0x02: 'MR_REG_M',
    0x03: 'OUT_X_H_M',
    0x04: 'OUT_X_L_M',
    0x05: 'OUT_Y_H_M',
    0x06: 'OUT_Y_L_M',
    0x07: 'OUT_Z_H_M',
    0x08: 'OUT_Z_L_M',
    0x09: 'SR_REG_M',
    0x0A: 'IRA_REG_M',
    0x0B: 'IRB_REG_M',
    0x0C: 'IRC_REG_M',
    0x31: 'TEMP_OUT_H_M',
    0x32: 'TEMP_OUT_L_M',
}

# CTRL_REG1_A.ODR<3:0> — output data rate codes.
ODR = {
    0x0: '0 Hz (power-down)',
    0x1: '1 Hz',
    0x2: '10 Hz',
    0x3: '25 Hz',
    0x4: '50 Hz',
    0x5: '100 Hz',
    0x6: '200 Hz',
    0x7: '400 Hz',
    0x8: '1.620 kHz (low-power only)',
    0x9: '1.344 kHz / 5.376 kHz',
}

# CTRL_REG4_A.FS<1:0> — full-scale range.
FS = {0x0: '±2 g', 0x1: '±4 g', 0x2: '±8 g', 0x3: '±16 g'}

# The operating mode follows LPen / HR (see the spec's Implementation Notes).
# The three-way split is derived from later ST documentation.
MODE = {
    (1, 0): 'low-power (8-bit)',
    (0, 0): 'normal (10-bit)',
    (0, 1): 'high-resolution (12-bit)',
}

# Sensitivity in mg/LSB, indexed by [mode][fs bits].
SENSITIVITY = {
    (1, 0): [16.0, 32.0, 64.0, 192.0],
    (0, 0): [4.0, 8.0, 16.0, 48.0],
    (0, 1): [1.0, 2.0, 4.0, 12.0],
}

# Right-shift that turns the raw 16-bit pair into counts, per mode.
SHIFT = {(1, 0): 8, (0, 0): 6, (0, 1): 4}

# CTRL_REG2_A.HPM<1:0> — high-pass filter mode.
HPM = {0x0: 'normal', 0x1: 'reference', 0x2: 'normal', 0x3: 'autoreset'}

# FIFO_CTRL_REG_A.FM<1:0> — FIFO mode.
FM = {0x0: 'bypass', 0x1: 'FIFO', 0x2: 'stream', 0x3: 'trigger'}

# CRB_REG_M.GN<2:0> — magnetometer full scale in µT.
GN = {
    0x0: '±130 µT',
    0x1: '±190 µT',
    0x2: '±250 µT',
    0x3: '±400 µT',
    0x4: '±470 µT',
    0x5: '±560 µT',
    0x6: '±810 µT',
}

# Magnetometer gains in LSB per gauss, indexed by the GN code.
MAG_GAINS = {
    0x0: (1100, 980),
    0x1: (855, 760),
    0x2: (670, 600),
    0x3: (450, 400),
    0x4: (400, 355),
    0x5: (330, 295),
    0x6: (230, 205),
}

# MR_REG_M.MD<1:0> — magnetometer operating mode.
MD = {0x0: 'continuous', 0x1: 'single', 0x2: 'sleep', 0x3: 'sleep (reserved)'}

# The magnetometer identity of a real part.
MAG_IDENTITY = [0x48, 0x34, 0x33]

ANN_REG_WRITE = 0
ANN_REG_READ = 1
ANN_PTR_WRITE = 2
ANN_WARNING = 3
# Conformance annotation pair (see specs/accelerometer/lsm303.md, Sigrok
# Decoder + Timing Constraints, and specs/accelerometer/lsm303_timing.conf).
ANN_ODR_START = 4
ANN_ODR_READY = 5


def _decode_ctrl1(raw):
    odr = (raw >> 4) & 0xF
    lpen = (raw >> 3) & 1
    axes = []
    if raw & 0x04:
        axes.append('Z')
    if raw & 0x02:
        axes.append('Y')
    if raw & 0x01:
        axes.append('X')
    parts = ['CTRL_REG1_A 0x%02X' % raw, 'ODR=%s' % ODR.get(odr, str(odr))]
    parts.append('axes=%s' % ('all' if len(axes) == 3 else ('none' if not axes else '+'.join(axes))))
    return ', '.join(parts)


def _decode_ctrl2(raw):
    hpm = (raw >> 6) & 0x3
    hpcf = (raw >> 4) & 0x3
    parts = ['CTRL_REG2_A 0x%02X' % raw, 'HPM=%s' % HPM.get(hpm, '?'), 'HPCF=%d' % hpcf]
    if raw & 0x08:
        parts.append('FDS=1 (filtered data to outputs)')
    if raw & 0x04:
        parts.append('HPCLICK=1')
    if raw & 0x02:
        parts.append('HPIS2=1')
    if raw & 0x01:
        parts.append('HPIS1=1')
    return ', '.join(parts)


def _decode_ctrl3(raw):
    parts = ['CTRL_REG3_A 0x%02X (INT1 routing)' % raw]
    if raw & 0x80:
        parts.append('CLICK')
    if raw & 0x40:
        parts.append('AOI1')
    if raw & 0x20:
        parts.append('AOI2')
    if raw & 0x10:
        parts.append('DRDY1')
    if raw & 0x04:
        parts.append('WTM')
    if raw & 0x02:
        parts.append('OVRN')
    return ', '.join(parts)


def _decode_ctrl4(raw):
    bdu = (raw >> 7) & 1
    fs = (raw >> 4) & 0x3
    hr = (raw >> 3) & 1
    return ('CTRL_REG4_A 0x%02X, BDU=%d, FS=%s, HR=%d, mode=%s'
            % (raw, bdu, FS.get(fs, str(fs)), hr,
               MODE.get((1 if not hr else 0, hr), '?')))


def _decode_ctrl5(raw):
    parts = ['CTRL_REG5_A 0x%02X' % raw]
    if raw & 0x80:
        parts.append('BOOT=1')
    if raw & 0x40:
        parts.append('FIFO_EN=1')
    if raw & 0x08:
        parts.append('LIR_INT1=1')
    if raw & 0x04:
        parts.append('D4D_INT1=1')
    if raw & 0x02:
        parts.append('LIR_INT2=1')
    if raw & 0x01:
        parts.append('D4D_INT2=1')
    return ', '.join(parts)


def _decode_ctrl6(raw):
    parts = ['CTRL_REG6_A 0x%02X (INT2 routing)' % raw]
    if raw & 0x80:
        parts.append('CLICK')
    if raw & 0x40:
        parts.append('INT1')
    if raw & 0x20:
        parts.append('INT2')
    if raw & 0x10:
        parts.append('BOOT_I1')
    parts.append('H_LACTIVE=%d' % ((raw >> 1) & 1))
    return ', '.join(parts)


def _decode_status(raw):
    flags = []
    if raw & 0x80:
        flags.append('ZYXOR')
    if raw & 0x08:
        flags.append('ZYXDA')
    if raw & 0x04:
        flags.append('ZDA')
    if raw & 0x02:
        flags.append('YDA')
    if raw & 0x01:
        flags.append('XDA')
    return 'STATUS_REG_A 0x%02X%s' % (raw, (' [' + ' '.join(flags) + ']') if flags else '')


def _decode_fifo_ctrl(raw):
    mode = (raw >> 6) & 0x3
    wtm = raw & 0x1F
    parts = ['FIFO_CTRL_REG_A 0x%02X, mode=%s, watermark=%d' % (raw, FM.get(mode, '?'), wtm)]
    if raw & 0x20:
        parts.append('trigger source=INT2')
    return ', '.join(parts)


def _decode_fifo_src(raw):
    flags = []
    if raw & 0x80:
        flags.append('WTM')
    if raw & 0x40:
        flags.append('OVRN')
    if raw & 0x20:
        flags.append('EMPTY')
    return 'FIFO_SRC_REG_A 0x%02X%s, level=%d' % (
        raw, (' [' + ' '.join(flags) + ']') if flags else '', raw & 0x1F)


def _decode_int_cfg(raw, name):
    parts = ['%s 0x%02X' % (name, raw)]
    if raw & 0x80:
        parts.append('AND mode')
    events = []
    if raw & 0x20:
        events.append('ZH')
    if raw & 0x10:
        events.append('ZL')
    if raw & 0x08:
        events.append('YH')
    if raw & 0x04:
        events.append('YL')
    if raw & 0x02:
        events.append('XH')
    if raw & 0x01:
        events.append('XL')
    if raw & 0x40:
        parts.append('6D/4D')
    parts.append('events=%s' % ('none' if not events else '+'.join(events)))
    return ', '.join(parts)


def _decode_int_source(raw, name):
    parts = ['%s 0x%02X' % (name, raw)]
    if raw & 0x40:
        parts.append('IA=1')
    return ', '.join(parts)


def _decode_click_cfg(raw):
    parts = ['CLICK_CFG_A 0x%02X' % raw]
    events = []
    if raw & 0x01:
        events.append('single X')
    if raw & 0x02:
        events.append('double X')
    if raw & 0x04:
        events.append('single Y')
    if raw & 0x08:
        events.append('double Y')
    if raw & 0x10:
        events.append('single Z')
    if raw & 0x20:
        events.append('double Z')
    parts.append('detectors=%s' % ('none' if not events else '+'.join(events)))
    return ', '.join(parts)


def _decode_cra_m(raw):
    do = (raw >> 2) & 0x7
    rates = ['0.75 Hz', '1.5 Hz', '3 Hz', '7.5 Hz', '15 Hz', '30 Hz', '75 Hz', '220 Hz']
    parts = ['CRA_REG_M 0x%02X, DO=%s' % (raw, rates[do])]
    if raw & 0x80:
        parts.append('TEMP_EN=1')
    return ', '.join(parts)


def _decode_crb_m(raw):
    gn = (raw >> 5) & 0x7
    return 'CRB_REG_M 0x%02X, GN=%s' % (raw, GN.get(gn, '?'))


def _decode_mr_m(raw):
    md = raw & 0x3
    return 'MR_REG_M 0x%02X, mode=%s' % (raw, MD.get(md, '?'))


def _decode_sr_m(raw):
    flags = []
    if raw & 0x02:
        flags.append('LOCK')
    if raw & 0x01:
        flags.append('DRDY')
    return 'SR_REG_M 0x%02X%s' % (raw, (' [' + ' '.join(flags) + ']') if flags else '')


def _decode_accel_reg(reg, raw):
    if reg == 0x20:
        return _decode_ctrl1(raw)
    if reg == 0x21:
        return _decode_ctrl2(raw)
    if reg == 0x22:
        return _decode_ctrl3(raw)
    if reg == 0x23:
        return _decode_ctrl4(raw)
    if reg == 0x24:
        return _decode_ctrl5(raw)
    if reg == 0x25:
        return _decode_ctrl6(raw)
    if reg == 0x26:
        return 'REFERENCE_A 0x%02X (%d)' % (raw, raw)
    if reg == 0x27:
        return _decode_status(raw)
    if reg == 0x2E:
        return _decode_fifo_ctrl(raw)
    if reg == 0x2F:
        return _decode_fifo_src(raw)
    if reg == 0x30:
        return _decode_int_cfg(raw, 'INT1_CFG_A')
    if reg == 0x31:
        return _decode_int_source(raw, 'INT1_SOURCE_A')
    if reg == 0x32:
        return 'INT1_THS_A 0x%02X (%d/128 full scale)' % (raw, raw)
    if reg == 0x33:
        return 'INT1_DURATION_A 0x%02X (%d samples)' % (raw, raw & 0x7F)
    if reg == 0x34:
        return _decode_int_cfg(raw, 'INT2_CFG_A')
    if reg == 0x35:
        return _decode_int_source(raw, 'INT2_SOURCE_A')
    if reg == 0x36:
        return 'INT2_THS_A 0x%02X (%d/128 full scale)' % (raw, raw)
    if reg == 0x37:
        return 'INT2_DURATION_A 0x%02X (%d samples)' % (raw, raw & 0x7F)
    if reg == 0x38:
        return _decode_click_cfg(raw)
    if reg == 0x39:
        return _decode_int_source(raw, 'CLICK_SRC_A')
    if reg == 0x3A:
        return 'CLICK_THS_A 0x%02X (%d/128 full scale)' % (raw, raw)
    if reg == 0x3B:
        return 'TIME_LIMIT_A 0x%02X (%d samples)' % (raw, raw & 0x7F)
    if reg == 0x3C:
        return 'TIME_LATENCY_A 0x%02X (%d samples)' % (raw, raw)
    if reg == 0x3D:
        return 'TIME_WINDOW_A 0x%02X (%d samples)' % (raw, raw)
    return 'Reg[0x%02X] 0x%02X' % (reg, raw)


def _decode_mag_reg(reg, raw):
    if reg == 0x00:
        return _decode_cra_m(raw)
    if reg == 0x01:
        return _decode_crb_m(raw)
    if reg == 0x02:
        return _decode_mr_m(raw)
    if reg == 0x09:
        return _decode_sr_m(raw)
    if reg == 0x0A:
        return 'IRA_REG_M 0x%02X%s' % (
            raw, ' (H ✓)' if raw == MAG_IDENTITY[0] else ' (expected 0x%02X!)' % MAG_IDENTITY[0])
    if reg == 0x0B:
        return 'IRB_REG_M 0x%02X%s' % (
            raw, ' (4 ✓)' if raw == MAG_IDENTITY[1] else ' (expected 0x%02X!)' % MAG_IDENTITY[1])
    if reg == 0x0C:
        return 'IRC_REG_M 0x%02X%s' % (
            raw, ' (3 ✓)' if raw == MAG_IDENTITY[2] else ' (expected 0x%02X!)' % MAG_IDENTITY[2])
    if reg in (0x31, 0x32):
        return 'TEMP_OUT 0x%02X (12-bit, 8 LSB/°C relative)' % raw
    return 'Reg[0x%02X] 0x%02X' % (reg, raw)


def _decode_temp(reg, buf):
    """Decode the 2-byte magnetometer temperature register pair.

    The value is a 12-bit right-justified reading at 8 LSB/°C; the datasheet
    gives no zero-degree reference, so it is a relative delta.
    """
    value = _int16_be(buf, 0) >> 4
    return 'Read TEMP_OUT: %+.2f °C relative (raw %d)' % (value / 8.0, value)


def _int16_le(buf, offset):
    """Sign-extend a little-endian 16-bit pair."""
    v = (buf[offset] & 0xFF) | ((buf[offset + 1] & 0xFF) << 8)
    return v if v < 0x8000 else v - 0x10000


def _int16_be(buf, offset):
    """Sign-extend a big-endian 16-bit pair."""
    v = ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF)
    return v if v < 0x8000 else v - 0x10000


def _warn_tag(msg):
    """Short (<= 5 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'identity' in m or 'device id' in m:
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
    id = 'lsm303'
    name = 'LSM303'
    longname = 'ST LSM303 3-axis accelerometer + magnetometer e-compass'
    desc = 'Decode LSM303 I2C register transactions (accelerometer and magnetometer dies).'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['lsm303']
    tags = ['IC', 'Sensor', 'Accelerometer']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read', 'Register read'),
        ('ptr-write', 'Register pointer write'),
        ('warning', 'Warning'),
        ('odr-start', 'odr_start: CTRL_REG1_A write with ODR != 0000'),
        ('odr-ready', 'odr_ready: first STATUS_REG_A read with ZYXDA=1'),
    )
    annotation_rows = (
        ('data', 'Data', (ANN_REG_WRITE, ANN_REG_READ, ANN_PTR_WRITE)),
        ('timing', 'Timing', (ANN_ODR_START, ANN_ODR_READY)),
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
        self.state = 'IDLE'
        self.addr = None
        self.is_read = False
        self.reg_ptr = None
        self.databuf = []
        self.ss_block = None
        # Last-seen accelerometer configuration, used to convert the output
        # registers into physical units.
        self.mode = (0, 1)
        self.fs_bits = 0
        # Last-seen magnetometer gain, used to convert the output registers
        # into µT.
        self.gn = 0
        # Set when CTRL_REG1_A was written with a non-zero ODR, so the first
        # STATUS_REG_A read with ZYXDA=1 can be annotated as odr_ready.
        self.odr_seen = False
        self._odr_ready_emitted = False

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _reg_name(self, reg):
        if self.addr == ADDR_ACCEL:
            return ACCEL_REGS.get(reg, 'Reg[0x%02X]' % reg)
        return MAG_REGS.get(reg, 'Reg[0x%02X]' % reg)

    def _decode_reg(self, reg, raw):
        if self.addr == ADDR_ACCEL:
            return _decode_accel_reg(reg, raw)
        return _decode_mag_reg(reg, raw)

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return

        reg = self.reg_ptr
        name = self._reg_name(reg) if reg is not None else '?'

        if self.is_read:
            if not self.databuf:
                return
            if self.addr == ADDR_ACCEL:
                # The 6-byte burst of the output registers (0x28..0x2D) is
                # decoded as a sample set; single-register reads are annotated
                # individually.
                if reg == 0x28 and len(self.databuf) == 6:
                    self._annotate_accel_sample(reg)
                    return
                if reg in (0x28, 0x2A, 0x2C):
                    # low byte of a 2-byte output register; wait for the next
                    # byte to assemble a 16-bit value (auto-increment).
                    return
            elif self.addr == ADDR_MAG:
                # The 6-byte burst of the output registers (0x03..0x08) is
                # decoded as a sample set.
                if reg == 0x03 and len(self.databuf) == 6:
                    self._annotate_mag_sample(reg)
                    return
                # The 2-byte temperature register pair is decoded as a
                # relative temperature.
                if reg == 0x31 and len(self.databuf) == 2:
                    temp = (_int16_be(self.databuf, 0) >> 4) / 8.0
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_REG_READ,
                              [_decode_temp(reg, self.databuf),
                               'R TEMP %+.2f °C' % temp,
                               'TEMP']])
                    return

            raw = self.databuf[0]
            decoded = self._decode_reg(reg, raw)
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_REG_READ,
                      ['Read %s: %s' % (name, decoded),
                       'R %s 0x%02X' % (name, raw)]])
            self._track_read(reg, raw, decoded)
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_PTR_WRITE,
                          ['Pointer → %s (0x%02X)' % (name, reg),
                           'PTR 0x%02X' % reg]])
                return
            raw = self.databuf[0]
            decoded = self._decode_reg(reg, raw)
            self.put(self.ss_block, self.es, self.out_ann,
                     [ANN_REG_WRITE,
                      ['Write %s: %s' % (name, decoded),
                       'W %s 0x%02X' % (name, raw)]])
            self._track_write(reg, raw)

    def _track_write(self, reg, raw):
        """Update the cached configuration and emit the timing markers."""
        if self.addr == ADDR_ACCEL:
            if reg == 0x20:
                odr = (raw >> 4) & 0xF
                if odr != 0:
                    # odr_start: CTRL_REG1_A written with a non-zero ODR.
                    self.odr_seen = True
                    self._odr_ready_emitted = False
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_ODR_START,
                              ['odr_start: CTRL_REG1_A=0x%02X (ODR=%s)' % (raw, ODR.get(odr, str(odr))),
                               'odr_start', 'O\u25b6']])
                else:
                    self.odr_seen = False
            elif reg == 0x23:
                # FS bits and the HR bit together define the operating mode.
                fs_bits = (raw >> 4) & 0x3
                hr = (raw >> 3) & 1
                self.fs_bits = fs_bits
                self.mode = (0 if hr else 1, hr)
        elif self.addr == ADDR_MAG:
            if reg == 0x01:
                self.gn = (raw >> 5) & 0x7

    def _track_read(self, reg, raw, decoded):
        """Emit the timing markers and check the magnetometer identity."""
        if self.addr == ADDR_ACCEL:
            if reg == 0x27 and self.odr_seen and not self._odr_ready_emitted:
                if raw & 0x08:
                    # odr_ready: first STATUS_REG_A read with ZYXDA=1.
                    self._odr_ready_emitted = True
                    self.put(self.ss_block, self.es, self.out_ann,
                             [ANN_ODR_READY,
                              ['odr_ready: first STATUS_REG_A read (0x%02X=%s)' % (raw, decoded),
                               'odr_ready', 'O\u2713']])
        elif self.addr == ADDR_MAG:
            if reg == 0x0A:
                # IRA_REG_M — the first byte of the identity triple.
                if raw != MAG_IDENTITY[0]:
                    self._warn(self.ss_block, self.es,
                               'Magnetometer identity IRA_REG_M: expected 0x%02X, got 0x%02X'
                               % (MAG_IDENTITY[0], raw))

    def _annotate_accel_sample(self, reg):
        """Annotate the 6-byte accelerometer output burst as a sample set."""
        buf = self.databuf
        shift = SHIFT.get(self.mode, 4)
        sens = SENSITIVITY.get(self.mode, [1.0, 2.0, 4.0, 12.0])[self.fs_bits] / 1000.0
        x = _int16_le(buf, 0) >> shift
        y = _int16_le(buf, 2) >> shift
        z = _int16_le(buf, 4) >> shift
        fs = FS.get(self.fs_bits, '?')
        text = ('Read OUT_X_L_A..OUT_Z_H_A: x=%+.4f g, y=%+.4f g, z=%+.4f g '
                '(raw %d/%d/%d, FS=%s)' % (x * sens, y * sens, z * sens, x, y, z, fs))
        self.put(self.ss_block, self.es, self.out_ann,
                 [ANN_REG_READ,
                  [text,
                   'R ACC %+.3f %+.3f %+.3f g' % (x * sens, y * sens, z * sens),
                   'ACC']])

    def _annotate_mag_sample(self, reg):
        """Annotate the 6-byte magnetometer output burst as a sample set."""
        buf = self.databuf
        # The magnetometer sends X, Z, Y on the wire.
        x = _int16_be(buf, 0)
        z = _int16_be(buf, 2)
        y = _int16_be(buf, 4)
        gain_x, gain_z = MAG_GAINS.get(self.gn, (1100, 980))
        ux = x / gain_x * 100.0
        uy = y / gain_x * 100.0
        uz = z / gain_z * 100.0
        fs = GN.get(self.gn, '?')
        text = ('Read OUT_X_H_M..OUT_Z_L_M: x=%+.2f uT, y=%+.2f uT, z=%+.2f uT '
                '(raw %d/%d/%d, %s)' % (ux, uy, uz, x, y, z, fs))
        self.put(self.ss_block, self.es, self.out_ann,
                 [ANN_REG_READ,
                  [text,
                   'R MAG %+.2f %+.2f %+.2f uT' % (ux, uy, uz),
                   'MAG']])

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state == 'GET_REG_PTR':
                pass  # pointer already set; keep databuf
            else:
                self._finish_transaction()
                self.databuf = []
                self.is_read = False
            self.ss_block = ss
            self.state = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            addr = pdata
            if addr not in ADDRS:
                self.state = 'IDLE'
                return
            self.addr = addr
            self.is_read = (ptype == 'ADDRESS READ')
            if self.is_read:
                self.databuf = []
                self.state = 'GET_DATA_READ'
            else:
                self.state = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            byte = pdata
            if self.state == 'GET_REG_PTR':
                self.reg_ptr = byte
                self.databuf = []
                self.state = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(byte)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state = 'IDLE'
            self.databuf = []
