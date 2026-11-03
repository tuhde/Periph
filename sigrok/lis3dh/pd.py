"""LIS3DH sigrok protocol decoder.

Decodes STMicroelectronics LIS3DH 3-axis MEMS accelerometer I²C
transactions (slave addresses 0x18 and 0x19) on top of the sigrok `i2c`
decoder.

The LIS3DH is register-addressed: the master writes an 8-bit sub-address
whose 7 LSbs are the register address and whose MSb enables address
auto-increment (a single-register access clears it), followed by a
repeated START and the read phase. The decoder strips the auto-increment
bit for the register name and reports the auto-increment state separately.

Annotated content:

- every register of the chip's register map, by name, with its decoded bit
  fields (CTRL_REG0..CTRL_REG6, TEMP_CFG_REG, STATUS_REG, STATUS_REG_AUX,
  FIFO_CTRL_REG, FIFO_SRC_REG, INTx_CFG, INTx_SRC, CLICK_CFG, CLICK_SRC,
  CLICK_THS, ...);
- the human-readable values of ODR, FS (±2/±4/±8/±16 g), the operating
  mode (LPen/HR: low-power 8-bit / normal 10-bit / high-resolution 12-bit),
  HPM, the FIFO mode (FM) and the self-test selection (ST);
- the 0x28-0x2D output burst assembled into signed 12/10/8-bit counts
  converted to *g* at the last-seen range and mode, the auxiliary ADC
  channels in counts and V, and the relative temperature in °C;
- WHO_AM_I reads checked against 0x33 (a mismatch raises a warning);
- the `odr_start` / `odr_ready` timing pair for the chip's first-data-ready
  timing constraint (see specs/accelerometer/lis3dh.md, Timing Constraints,
  and specs/accelerometer/lis3dh_timing.conf).
"""

import sigrokdecode as srd

ADDRS = {0x18, 0x19}

REGS = {
    0x07: 'STATUS_REG_AUX',
    0x08: 'OUT_ADC1_L',
    0x09: 'OUT_ADC1_H',
    0x0A: 'OUT_ADC2_L',
    0x0B: 'OUT_ADC2_H',
    0x0C: 'OUT_ADC3_L',
    0x0D: 'OUT_ADC3_H',
    0x0F: 'WHO_AM_I',
    0x1E: 'CTRL_REG0',
    0x1F: 'TEMP_CFG_REG',
    0x20: 'CTRL_REG1',
    0x21: 'CTRL_REG2',
    0x22: 'CTRL_REG3',
    0x23: 'CTRL_REG4',
    0x24: 'CTRL_REG5',
    0x25: 'CTRL_REG6',
    0x26: 'REFERENCE',
    0x27: 'STATUS_REG',
    0x28: 'OUT_X_L',
    0x29: 'OUT_X_H',
    0x2A: 'OUT_Y_L',
    0x2B: 'OUT_Y_H',
    0x2C: 'OUT_Z_L',
    0x2D: 'OUT_Z_H',
    0x2E: 'FIFO_CTRL_REG',
    0x2F: 'FIFO_SRC_REG',
    0x30: 'INT1_CFG',
    0x31: 'INT1_SRC',
    0x32: 'INT1_THS',
    0x33: 'INT1_DURATION',
    0x34: 'INT2_CFG',
    0x35: 'INT2_SRC',
    0x36: 'INT2_THS',
    0x37: 'INT2_DURATION',
    0x38: 'CLICK_CFG',
    0x39: 'CLICK_SRC',
    0x3A: 'CLICK_THS',
    0x3B: 'TIME_LIMIT',
    0x3C: 'TIME_LATENCY',
    0x3D: 'TIME_WINDOW',
    0x3E: 'ACT_THS',
    0x3F: 'ACT_DUR',
}

# Short (narrow-zoom) abbreviations of the register names.
SHORT_REGS = {
    'STATUS_REG_AUX': 'SAUX',
    'OUT_ADC1_L': 'A1L', 'OUT_ADC1_H': 'A1H',
    'OUT_ADC2_L': 'A2L', 'OUT_ADC2_H': 'A2H',
    'OUT_ADC3_L': 'A3L', 'OUT_ADC3_H': 'A3H',
    'WHO_AM_I': 'ID',
    'CTRL_REG0': 'C0', 'TEMP_CFG_REG': 'TCFG',
    'CTRL_REG1': 'C1', 'CTRL_REG2': 'C2', 'CTRL_REG3': 'C3',
    'CTRL_REG4': 'C4', 'CTRL_REG5': 'C5', 'CTRL_REG6': 'C6',
    'REFERENCE': 'REF', 'STATUS_REG': 'STAT',
    'OUT_X_L': 'XL', 'OUT_X_H': 'XH',
    'OUT_Y_L': 'YL', 'OUT_Y_H': 'YH',
    'OUT_Z_L': 'ZL', 'OUT_Z_H': 'ZH',
    'FIFO_CTRL_REG': 'FCTL', 'FIFO_SRC_REG': 'FSRC',
    'INT1_CFG': '1CFG', 'INT1_SRC': '1SRC',
    'INT1_THS': '1THS', 'INT1_DURATION': '1DUR',
    'INT2_CFG': '2CFG', 'INT2_SRC': '2SRC',
    'INT2_THS': '2THS', 'INT2_DURATION': '2DUR',
    'CLICK_CFG': 'CCFG', 'CLICK_SRC': 'CSRC', 'CLICK_THS': 'CTHS',
    'TIME_LIMIT': 'TLIM', 'TIME_LATENCY': 'TLAT', 'TIME_WINDOW': 'TWIN',
    'ACT_THS': 'ATHS', 'ACT_DUR': 'ADUR',
}

# Expected read length per register (None = variable / handled elsewhere).
EXPECTED_LEN = {}
for _r in (0x07, 0x09, 0x0B, 0x0D, 0x0F, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23,
           0x24, 0x25, 0x26, 0x27, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34,
           0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F):
    EXPECTED_LEN[_r] = 1
EXPECTED_LEN[0x08] = 2
EXPECTED_LEN[0x0A] = 2
EXPECTED_LEN[0x0C] = 2

# CTRL_REG1.ODR<3:0> (bits 7:4).
ODR_NAMES = {
    0x0: 'power-down',
    0x1: '1 Hz',
    0x2: '10 Hz',
    0x3: '25 Hz',
    0x4: '50 Hz',
    0x5: '100 Hz',
    0x6: '200 Hz',
    0x7: '400 Hz',
    0x8: '1.620 kHz',
    0x9: '1.344/5.376 kHz',
}

# CTRL_REG4.FS<1:0> (bits 5:4).
FS_NAMES = {0: '±2 g', 1: '±4 g', 2: '±8 g', 3: '±16 g'}

# Operating mode from LPen (CTRL_REG1 bit 3) and HR (CTRL_REG4 bit 3).
MODE_NAMES = {
    'lp': 'low-power 8-bit',
    'normal': 'normal 10-bit',
    'hr': 'high-resolution 12-bit',
    'invalid': 'invalid (LPen=1, HR=1)',
}

# Sensitivity in mg/LSB by mode and FS code (datasheet Table 4). The ±16 g
# high-resolution value is 12 mg/LSB, not 8 mg/LSB.
SENSITIVITY_MG = {
    'hr':     {0: 1, 1: 2, 2: 4, 3: 12},
    'normal': {0: 4, 1: 8, 2: 16, 3: 48},
    'lp':     {0: 16, 1: 32, 2: 64, 3: 192},
}

# Arithmetic right-shift applied to the left-justified 16-bit output pair.
OUTPUT_SHIFT = {'hr': 4, 'normal': 6, 'lp': 8}

# Right-shift for the auxiliary ADC output (10-bit, 8-bit in low-power).
ADC_SHIFT = {'hr': 6, 'normal': 6, 'lp': 8}

# CTRL_REG2.HPM<1:0> (bits 7:6).
HPM_NAMES = {0: 'normal', 1: 'reference', 2: 'normal', 3: 'auto-reset on interrupt'}

# FIFO_CTRL_REG.FM<1:0> (bits 7:6).
FM_NAMES = {0: 'bypass', 1: 'FIFO', 2: 'stream', 3: 'stream-to-FIFO'}

# CTRL_REG4.ST<1:0> (bits 2:1).
ST_NAMES = {0: 'off', 1: 'self-test 0', 2: 'self-test 1', 3: 'not allowed'}

# STATUS_REG (0x27) bits.
STATUS_FIELDS = [
    (0x80, 'ZYXOR'), (0x40, 'ZOR'), (0x20, 'YOR'), (0x10, 'XOR'),
    (0x08, 'ZYXDA'), (0x04, 'ZDA'), (0x02, 'YDA'), (0x01, 'XDA'),
]

# STATUS_REG_AUX (0x07) bits.
STATUS_AUX_FIELDS = [
    (0x80, '321OR'), (0x40, '3OR'), (0x20, '2OR'), (0x10, '1OR'),
    (0x08, '321DA'), (0x04, '3DA'), (0x02, '2DA'), (0x01, '1DA'),
]

# FIFO_SRC_REG (0x2F) bits.
FIFO_SRC_FIELDS = [(0x80, 'WTM'), (0x40, 'OVRN_FIFO'), (0x20, 'EMPTY')]

# INTx_SRC (0x31 / 0x35) bits.
INT_SRC_FIELDS = [
    (0x40, 'IA'), (0x20, 'ZH'), (0x10, 'ZL'), (0x08, 'YH'),
    (0x04, 'YL'), (0x02, 'XH'), (0x01, 'XL'),
]

# INTx_CFG (0x30 / 0x34) event-enable bits (ZHIE..XLIE).
INT_CFG_EVENTS = [
    (0x20, 'ZHIE'), (0x10, 'ZLIE'), (0x08, 'YHIE'),
    (0x04, 'YLIE'), (0x02, 'XHIE'), (0x01, 'XLIE'),
]

# CLICK_CFG (0x38) bits.
CLICK_CFG_FIELDS = [
    (0x20, 'ZD'), (0x10, 'ZS'), (0x08, 'YD'),
    (0x04, 'YS'), (0x02, 'XD'), (0x01, 'XS'),
]

# CLICK_SRC (0x39) bits.
CLICK_SRC_FIELDS = [
    (0x40, 'IA'), (0x20, 'DCLICK'), (0x10, 'SCLICK'), (0x08, 'Sign'),
    (0x04, 'Z'), (0x02, 'Y'), (0x01, 'X'),
]

# CTRL_REG3 (0x22) — interrupt routing to INT1.
CTRL_REG3_FIELDS = [
    (0x80, 'I1_CLICK'), (0x40, 'I1_IA1'), (0x20, 'I1_IA2'),
    (0x10, 'I1_ZYXDA'), (0x08, 'I1_321DA'), (0x04, 'I1_WTM'),
    (0x02, 'I1_OVERRUN'),
]

# CTRL_REG6 (0x25) — interrupt routing to INT2.
CTRL_REG6_FIELDS = [
    (0x80, 'I2_CLICK'), (0x40, 'I2_IA1'), (0x20, 'I2_IA2'),
    (0x10, 'I2_BOOT'), (0x08, 'I2_ACT'), (0x02, 'INT_POLARITY'),
]

# The three output-register pairs and the axis each one carries.
AXIS_REGS = {0x28: 'X', 0x2A: 'Y', 0x2C: 'Z'}

# The three auxiliary ADC channels.
ADC_REGS = {0x08: 1, 0x0A: 2, 0x0C: 3}

# Registers that are reserved and must never be written (writing them may
# damage the part): 0x00-0x06, 0x0E, 0x10-0x1D.
RESERVED_REGS = frozenset(list(range(0x00, 0x07)) + [0x0E] +
                          list(range(0x10, 0x1E)))

# Annotation classes.
ANN_REG_WRITE = 0
ANN_REG_READ  = 1
ANN_PTR_WRITE = 2
ANN_DATA      = 3
ANN_STATUS    = 4
# Conformance annotation pair for the "first_data_ready" timing constraint
# (see specs/accelerometer/lis3dh.md, Timing Constraints, and
# specs/accelerometer/lis3dh_timing.conf): a CTRL_REG1 write with ODR != 0
# starts the conversions (odr_start), the first STATUS_REG read with
# ZYXDA = 1 shows the first sample set is ready (odr_ready).
ANN_ODR_START = 5
ANN_ODR_READY = 6
ANN_WARNING   = 7


def _mode_from(lpen, hr):
    """Operating mode from LPen (CTRL_REG1 bit 3) and HR (CTRL_REG4 bit 3)."""
    if lpen:
        return 'invalid' if hr else 'lp'
    return 'hr' if hr else 'normal'


def _signed16(value):
    """Sign-extend a 16-bit two's complement value."""
    return value - 0x10000 if value & 0x8000 else value


def _signed8(value):
    """Sign-extend an 8-bit two's complement value."""
    return value - 0x100 if value & 0x80 else value


def _short_reg(name):
    """Short (2-8 char) abbreviation of a register name."""
    if name in SHORT_REGS:
        return SHORT_REGS[name]
    if name.startswith('Reg[0x'):
        return name[4:9]
    return name[:8]


def _odr_name(code, mode):
    """Human-readable ODR value; code 9 depends on the operating mode."""
    if code == 0x9:
        return '5.376 kHz' if mode == 'lp' else '1.344 kHz'
    return ODR_NAMES.get(code, '?')


def _flag_list(raw, fields):
    """Names of the set bits in `fields`."""
    return [name for bit, name in fields if raw & bit]


def _decode_ctrl_reg0(raw):
    parts = ['SDO_PU_DISC=%s' % ('1 (pull-up disconnected)' if raw & 0x80
                                 else '0 (pull-up connected)')]
    if raw & 0x10:
        parts.append('bit4=1 (must stay 1)')
    return ', '.join(parts)


def _decode_temp_cfg(raw):
    return 'ADC_EN=%s, TEMP_EN=%s' % ('on' if raw & 0x80 else 'off',
                                       'on' if raw & 0x40 else 'off')


def _decode_ctrl_reg1(raw, lpen, hr):
    odr = (raw >> 4) & 0x0F
    mode = _mode_from(lpen, hr)
    axes = []
    if raw & 0x01:
        axes.append('X')
    if raw & 0x02:
        axes.append('Y')
    if raw & 0x04:
        axes.append('Z')
    return 'ODR=%s, LPen=%d, axes=%s' % (
        _odr_name(odr, mode), lpen, '+'.join(axes) if axes else 'none')


def _decode_ctrl_reg2(raw):
    hpm = (raw >> 6) & 0x03
    hpcf = (raw >> 4) & 0x03
    parts = ['HPM=%s' % HPM_NAMES.get(hpm, '?'), 'HPCF=%d' % hpcf]
    if raw & 0x08:
        parts.append('FDS=1')
    if raw & 0x04:
        parts.append('HPCLICK=1')
    if raw & 0x02:
        parts.append('HP_IA2=1')
    if raw & 0x01:
        parts.append('HP_IA1=1')
    return ', '.join(parts)


def _decode_ctrl_reg3(raw):
    flags = _flag_list(raw, CTRL_REG3_FIELDS)
    return 'INT1 routing: %s' % (', '.join(flags) if flags else 'none')


def _decode_ctrl_reg4(raw, lpen):
    bdu = 1 if raw & 0x80 else 0
    ble = 1 if raw & 0x40 else 0
    fs = (raw >> 4) & 0x03
    hr = (raw >> 3) & 0x01
    st = (raw >> 1) & 0x03
    sim = raw & 0x01
    mode = _mode_from(lpen, hr)
    return ('BDU=%d, BLE=%d, FS=%s, mode=%s, ST=%s, SIM=%d (%s)' % (
        bdu, ble, FS_NAMES.get(fs, '?'), MODE_NAMES.get(mode, '?'),
        ST_NAMES.get(st, '?'), sim,
        '3-wire SPI' if sim else '4-wire SPI'))


def _decode_ctrl_reg5(raw):
    parts = []
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
    return 'CTRL_REG5: %s' % (', '.join(parts) if parts else 'no flags set')


def _decode_ctrl_reg6(raw):
    flags = _flag_list(raw, CTRL_REG6_FIELDS)
    return 'INT2 routing: %s' % (', '.join(flags) if flags else 'none')


def _decode_fifo_ctrl(raw):
    fm = (raw >> 6) & 0x03
    tr = (raw >> 5) & 0x01
    fth = raw & 0x1F
    return 'FM=%s, TR=%s, FTH=%d' % (
        FM_NAMES.get(fm, '?'), 'INT2' if tr else 'INT1', fth)


def _decode_fifo_src(raw):
    flags = _flag_list(raw, FIFO_SRC_FIELDS)
    return 'FIFO_SRC: %s, FSS=%d' % (
        ', '.join(flags) if flags else 'no flags set', raw & 0x1F)


def _decode_int_cfg(raw):
    aoi = (raw >> 7) & 0x01
    sixd = (raw >> 6) & 0x01
    if aoi and sixd:
        mode = '6D position recognition'
    elif aoi:
        mode = 'AND combination'
    elif sixd:
        mode = '6D movement recognition'
    else:
        mode = 'OR combination'
    events = _flag_list(raw, INT_CFG_EVENTS)
    return 'mode=%s, events=%s' % (mode, '+'.join(events) if events else 'none')


def _decode_int_src(raw):
    flags = _flag_list(raw, INT_SRC_FIELDS)
    return 'INT_SRC: %s' % (', '.join(flags) if flags else 'none')


def _decode_click_cfg(raw):
    flags = _flag_list(raw, CLICK_CFG_FIELDS)
    return 'CLICK_CFG: %s' % (', '.join(flags) if flags else 'none')


def _decode_click_src(raw):
    flags = _flag_list(raw, CLICK_SRC_FIELDS)
    return 'CLICK_SRC: %s' % (', '.join(flags) if flags else 'none')


def _decode_click_ths(raw):
    return 'LIR_Click=%d, threshold=%d' % (1 if raw & 0x80 else 0, raw & 0x7F)


def _decode_whoami(raw):
    return 'WHO_AM_I 0x%02X%s' % (raw, ' (LIS3DH ✓)' if raw == 0x33
                                   else ' (expected 0x33!)')


def _decode_reg(reg, raw, lpen, hr):
    """Long-form decode of a single-byte register value."""
    if reg == 0x07:
        flags = _flag_list(raw, STATUS_AUX_FIELDS)
        return 'STATUS_REG_AUX: %s' % (', '.join(flags) if flags else 'no flags set')
    if reg == 0x0F:
        return _decode_whoami(raw)
    if reg == 0x1E:
        return _decode_ctrl_reg0(raw)
    if reg == 0x1F:
        return _decode_temp_cfg(raw)
    if reg == 0x20:
        return _decode_ctrl_reg1(raw, lpen, hr)
    if reg == 0x21:
        return _decode_ctrl_reg2(raw)
    if reg == 0x22:
        return _decode_ctrl_reg3(raw)
    if reg == 0x23:
        return _decode_ctrl_reg4(raw, lpen)
    if reg == 0x24:
        return _decode_ctrl_reg5(raw)
    if reg == 0x25:
        return _decode_ctrl_reg6(raw)
    if reg == 0x26:
        return 'REFERENCE=0x%02X (%d)' % (raw, raw)
    if reg == 0x27:
        flags = _flag_list(raw, STATUS_FIELDS)
        return 'STATUS_REG: %s' % (', '.join(flags) if flags else 'no flags set')
    if reg == 0x2E:
        return _decode_fifo_ctrl(raw)
    if reg == 0x2F:
        return _decode_fifo_src(raw)
    if reg in (0x30, 0x34):
        return _decode_int_cfg(raw)
    if reg in (0x31, 0x35):
        return _decode_int_src(raw)
    if reg in (0x32, 0x36):
        return 'threshold=%d (mg/LSb scale)' % (raw & 0x7F)
    if reg in (0x33, 0x37):
        return 'duration=%d (1 LSb = 1/ODR)' % (raw & 0x7F)
    if reg == 0x38:
        return _decode_click_cfg(raw)
    if reg == 0x39:
        return _decode_click_src(raw)
    if reg == 0x3A:
        return _decode_click_ths(raw)
    if reg == 0x3B:
        return 'TIME_LIMIT=%d (1 LSb = 1/ODR)' % raw
    if reg == 0x3C:
        return 'TIME_LATENCY=%d (1 LSb = 1/ODR)' % raw
    if reg == 0x3D:
        return 'TIME_WINDOW=%d (1 LSb = 1/ODR)' % raw
    if reg == 0x3E:
        return 'ACT_THS=%d (mg/LSb scale)' % (raw & 0x7F)
    if reg == 0x3F:
        return 'ACT_DUR=%d (1 LSb = (8·ACT_DUR+1)/ODR)' % raw
    return '0x%02X' % raw


def _warn_tag(msg):
    """Short (<= 8 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'who_am_i' in m or 'chip id' in m or 'device id' in m:
        return 'ID?'
    if 'address' in m:
        return 'ADDR?'
    if any(k in m for k in ('length', 'byte', 'short', 'missing', 'expected',
                            'no data', 'empty')):
        return 'LEN?'
    if any(k in m for k in ('unknown', 'unexpected', 'invalid', 'undefined',
                            'reserved', 'out of range', 'violation')):
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
    id = 'lis3dh'
    name = 'LIS3DH'
    longname = 'STMicroelectronics LIS3DH 3-axis MEMS accelerometer'
    desc = 'Decode LIS3DH I2C register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['lis3dh']
    tags = ['IC', 'Sensor', 'Accelerometer']

    annotations = (
        ('reg-write', 'Register write'),
        ('reg-read',  'Register read'),
        ('ptr-write', 'Register pointer write'),
        ('data',      'Decoded measurement'),
        ('status',    'Status flags'),
        ('odr-start', 'odr_start: output data rate started'),
        ('odr-ready', 'odr_ready: first sample set ready'),
        ('warning',   'Warning'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_REG_WRITE, ANN_REG_READ, ANN_PTR_WRITE, ANN_DATA)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_ODR_START, ANN_ODR_READY)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.state    = 'IDLE'
        self.addr     = None
        self.is_read  = False
        self.reg_ptr  = None      # 7-bit register address (auto-inc stripped)
        self.raw_sub  = None      # raw sub-address byte as written
        self.auto_inc = False
        self.databuf  = []
        self.ss_block = None
        # Last-seen configuration: the chip resets to ±2 g, 10-bit (normal)
        # mode, power-down (CTRL_REG1 = 0x07, CTRL_REG4 = 0x00).
        self.lpen     = 0
        self.hr       = 0
        self.fs_code  = 0
        self.odr_code = 0
        self.temp_en  = False
        self._py_seen = set()

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    # ---- output helpers --------------------------------------------------

    def put(self, ss, es, out, data):
        if out == self.out_ann:
            tiered = self.__dict__.get('_tiered')
            if tiered is None:
                tiered = self._tiered = {
                    c for rid, _title, classes in self.annotation_rows
                    if rid not in ('timing', 'warnings') for c in classes}
            if data[0] in tiered:
                data = [data[0], _with_short(data[1])]
        super().put(ss, es, out, data)

    def _ann(self, ss, es, cls, text):
        """Emit one annotation; `text` is one string or a list of long-to-short strings."""
        strs = [text] if isinstance(text, str) else list(text)
        self.put(ss, es, self.out_ann, [cls, strs])

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _emit_python(self, ss, es, kind, value):
        """OUTPUT_PYTHON mirror of a data/status annotation: a
        (kind, value) tuple, kind uppercase."""
        out_py = self.__dict__.get('out_python')
        if out_py is None:
            return
        seen = self.__dict__.get('_py_seen')
        if seen is None:
            seen = self._py_seen = set()
        key = (kind, ss, es)
        if key in seen:
            return
        seen.add(key)
        super().put(ss, es, out_py, (kind, value))

    # ---- configuration tracking ------------------------------------------

    def _mode(self):
        return _mode_from(self.lpen, self.hr)

    def _track_config(self, reg, raw):
        """Remember the last-seen range / mode / ODR so the output burst can
        be converted with the right sensitivity and shift."""
        if reg == 0x20:
            self.odr_code = (raw >> 4) & 0x0F
            self.lpen = (raw >> 3) & 0x01
        elif reg == 0x23:
            self.fs_code = (raw >> 4) & 0x03
            self.hr = (raw >> 3) & 0x01
        elif reg == 0x1F:
            self.temp_en = bool(raw & 0x40)

    def _sensitivity_mg(self):
        table = SENSITIVITY_MG.get(self._mode())
        if table is None:
            table = SENSITIVITY_MG['normal']
        return table[self.fs_code]

    def _shift(self):
        return OUTPUT_SHIFT.get(self._mode(), 6)

    # ---- transaction handling --------------------------------------------

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.reg_ptr is not None:
                # The register pointer survives the repeated start: the
                # pointer write ends here, the read phase follows.
                self._finish_transaction(keep_pointer=True)
            else:
                self._finish_transaction()
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                self._warn(ss, es,
                           'Unknown I2C address 0x%02X (not a LIS3DH)' % pdata)
                self.state = 'IDLE'
                return
            self.addr = pdata
            self.is_read = (ptype == 'ADDRESS READ')
            self.databuf = []
            if self.is_read:
                self.state = 'GET_DATA_READ'
            else:
                self.reg_ptr = None
                self.raw_sub = None
                self.auto_inc = False
                self.state = 'GET_REG_PTR'

        elif ptype == 'DATA WRITE':
            byte = pdata
            if self.state == 'GET_REG_PTR':
                # Sub-address: 7 LSbs are the register, MSb = auto-increment.
                self.raw_sub = byte
                self.reg_ptr = byte & 0x7F
                self.auto_inc = bool(byte & 0x80)
                self.databuf = []
                self.state = 'GET_DATA_WRITE'
            elif self.state == 'GET_DATA_WRITE':
                self.databuf.append(byte)

        elif ptype == 'DATA READ':
            if self.state == 'GET_DATA_READ':
                self.databuf.append(pdata)

        elif ptype == 'STOP':
            self._finish_transaction()
            self.state   = 'IDLE'
            self.databuf = []

    def _finish_transaction(self, keep_pointer=False):
        """Emit the annotations of the finished transaction."""
        reg = self.reg_ptr
        if reg is None:
            if self.is_read and self.databuf:
                self._warn(self.ss_block, self.es,
                           'Read of %d bytes without a register pointer'
                           % len(self.databuf))
            if not keep_pointer:
                self.raw_sub = None
                self.auto_inc = False
            return
        name = REGS.get(reg, 'Reg[0x%02X]' % reg)
        if self.is_read:
            self._emit_read(reg, name)
        else:
            self._emit_write(reg, name)
        if not keep_pointer:
            self.reg_ptr = None
            self.raw_sub = None
            self.auto_inc = False

    # ---- writes -----------------------------------------------------------

    def _emit_write(self, reg, name):
        ss, es = self.ss_block, self.es
        buf = self.databuf
        inc = ' (auto-inc)' if self.auto_inc else ''
        abbr = _short_reg(name)

        if not buf:
            # Pointer-only write: the register address itself.
            self._ann(ss, es, ANN_PTR_WRITE,
                      ['Pointer → %s (0x%02X)%s' % (name, reg, inc),
                       'PTR %s 0x%02X' % (name, reg),
                       'PTR:%02X' % reg])
            self._emit_python(ss, es, 'REG_WRITE', (reg, b''))
            return

        if len(buf) == 1:
            raw = buf[0]
            desc = _decode_reg(reg, raw, self.lpen, self.hr)
            self._ann(ss, es, ANN_REG_WRITE,
                      ['Write %s: 0x%02X%s — %s' % (name, raw, inc, desc),
                       'W %s 0x%02X' % (name, raw),
                       '%s:%02X' % (abbr, raw)])
            self._emit_python(ss, es, 'REG_WRITE', (reg, bytes(buf)))
            self._track_config(reg, raw)
            if reg in RESERVED_REGS:
                self._warn(ss, es,
                           'Write to reserved register 0x%02X (may damage the part)'
                           % reg)
            elif reg == 0x1E and (raw & 0x7F) != 0x10:
                # Only bit 7 (SDO_PU_DISC) is writable; bit 4 must stay 1 and
                # bits 6:5 and 3:0 must stay 0.
                self._warn(ss, es,
                           'CTRL_REG0 reserved bits changed (0x%02X): bit 4 must '
                           'stay 1, bits 6:5 and 3:0 must stay 0' % raw)
            if reg == 0x20 and ((raw >> 4) & 0x0F):
                # odr_start: CTRL_REG1 written with ODR != 0000.
                self._ann(ss, es, ANN_ODR_START,
                          ['odr_start: CTRL_REG1 write with ODR=%s'
                           % _odr_name((raw >> 4) & 0x0F, self._mode()),
                           'odr_start', 'ODR\u25b6'])
            return

        self._warn(ss, es, 'Unexpected write length %d for %s' % (len(buf), name))
        self._emit_python(ss, es, 'REG_WRITE', (reg, bytes(buf)))

    # ---- reads ------------------------------------------------------------

    def _emit_read(self, reg, name):
        ss, es = self.ss_block, self.es
        buf = self.databuf
        inc = ' (auto-inc)' if self.auto_inc else ''
        abbr = _short_reg(name)

        if not buf:
            self._warn(ss, es, 'Read of %s returned no data' % name)
            return

        if reg in AXIS_REGS and len(buf) >= 2:
            self._emit_axis_read(reg, name, buf, inc)
            return

        if reg in ADC_REGS and len(buf) >= 2:
            self._emit_adc_read(reg, name, buf, inc)
            return

        if reg == 0x0D and len(buf) == 1:
            self._emit_temperature_read(reg, name, buf[0], inc)
            return

        if len(buf) == 1:
            raw = buf[0]
            desc = _decode_reg(reg, raw, self.lpen, self.hr)
            self._ann(ss, es, ANN_REG_READ,
                      ['Read %s: 0x%02X%s — %s' % (name, raw, inc, desc),
                       'R %s 0x%02X' % (name, raw),
                       '%s:%02X' % (abbr, raw)])
            self._emit_python(ss, es, 'REG_READ', (reg, bytes(buf)))
            self._track_config(reg, raw)
            if reg == 0x0F and raw != 0x33:
                self._warn(ss, es,
                           'WHO_AM_I mismatch: got 0x%02X, expected 0x33' % raw)
            if reg in (0x07, 0x27, 0x2F, 0x31, 0x35, 0x39):
                # STATUS_REG / STATUS_REG_AUX / FIFO_SRC_REG / INTx_SRC /
                # CLICK_SRC only report state - they get their own row.
                self._emit_status(reg, name, raw)
            if reg == 0x27 and (raw & 0x08):
                # odr_ready: first STATUS_REG read with ZYXDA = 1.
                self._ann(ss, es, ANN_ODR_READY,
                          ['odr_ready: STATUS_REG read with ZYXDA=1 '
                           '(0x%02X)' % raw,
                           'odr_ready', 'ODR\u2713'])
            return

        # Multi-byte read of a register that is not a known pair/burst.
        expected = EXPECTED_LEN.get(reg)
        if expected is not None and len(buf) != expected:
            self._warn(ss, es, 'Unexpected read length %d for %s'
                       % (len(buf), name))
        last = REGS.get((reg + len(buf) - 1) & 0x7F, 'Reg[0x%02X]' % ((reg + len(buf) - 1) & 0x7F))
        hexbytes = ' '.join('%02X' % b for b in buf)
        self._ann(ss, es, ANN_REG_READ,
                  ['Read %s..%s: %s%s' % (name, last, hexbytes, inc),
                   'R %s..%s %dB' % (name, last, len(buf)),
                   '%s+%dB' % (abbr, len(buf))])
        self._emit_python(ss, es, 'REG_READ', (reg, bytes(buf)))
        for i, raw in enumerate(buf):
            self._track_config((reg + i) & 0x7F, raw)

    def _emit_axis_read(self, reg, name, buf, inc):
        """Assemble the output-register burst into signed counts and *g*."""
        ss, es = self.ss_block, self.es
        shift = self._shift()
        sens = self._sensitivity_mg()
        axes = []
        i = 0
        while i + 1 < len(buf):
            axis = AXIS_REGS.get(reg + i)
            if axis is None:
                break
            raw16 = _signed16(buf[i] | (buf[i + 1] << 8))
            counts = raw16 >> shift
            axes.append((axis, counts, counts * sens / 1000.0))
            i += 2
        if not axes:
            self._warn(ss, es, 'Unexpected read length %d for %s' % (len(buf), name))
            return
        last_reg = reg + (len(axes) * 2) - 1
        last_name = REGS.get(last_reg, 'Reg[0x%02X]' % last_reg)
        gtext = ', '.join('%s=%+.3f g' % (a, g) for a, _c, g in axes)
        ctext = ', '.join('%s raw %+d' % (a, c) for a, c, _g in axes)
        mode = MODE_NAMES.get(self._mode(), '?')
        self._ann(ss, es, ANN_REG_READ,
                  ['Read %s..%s: %d bytes%s' % (name, last_name, len(buf), inc),
                   'R %s..%s %dB' % (name, last_name, len(buf)),
                   '%s+%dB' % (_short_reg(name), len(buf))])
        self._ann(ss, es, ANN_DATA,
                  ['%s: %s (%s) @ %s, %d mg/LSB'
                   % (name, gtext, ctext, mode, sens),
                   '%s' % gtext,
                   ' '.join(a for a, _c, _g in axes)])
        self._emit_python(ss, es, 'REG_READ', (reg, bytes(buf)))
        self._emit_python(ss, es, 'ACCEL', tuple(g for _a, _c, g in axes))
        self._emit_python(ss, es, 'RAW', tuple(c for _a, c, _g in axes))

    def _emit_adc_read(self, reg, name, buf, inc):
        """Auxiliary ADC channel: raw counts and the derived input voltage."""
        ss, es = self.ss_block, self.es
        channel = ADC_REGS[reg]
        raw16 = _signed16(buf[0] | (buf[1] << 8))
        shift = ADC_SHIFT.get(self._mode(), 6)
        counts = raw16 >> shift
        volts = 1.200 + counts * (0.400 / 512.0)
        note = ''
        if channel == 3 and self.temp_en:
            note = ' (TEMP_EN=1: channel 3 is the temperature sensor)'
        self._ann(ss, es, ANN_REG_READ,
                  ['Read %s: 0x%02X%02X%s' % (name, buf[0], buf[1], inc),
                   'R %s %02X%02X' % (name, buf[0], buf[1]),
                   '%s:%02X' % (_short_reg(name), buf[0])])
        self._ann(ss, es, ANN_DATA,
                  ['ADC%d: raw %+d counts, %.3f V (input range 1200 mV ± 400 mV)%s'
                   % (channel, counts, volts, note),
                   'ADC%d %+d, %.3f V' % (channel, counts, volts),
                   'ADC%d' % channel])
        self._emit_python(ss, es, 'REG_READ', (reg, bytes(buf)))
        self._emit_python(ss, es, 'ADC', (channel, volts))

    def _emit_temperature_read(self, reg, name, raw, inc):
        """Temperature delta: 8-bit two's complement in OUT_ADC3_H, 1 digit/°C."""
        ss, es = self.ss_block, self.es
        delta = _signed8(raw)
        note = '' if self.temp_en else ' (TEMP_EN=0: ADC channel 3 high byte)'
        self._ann(ss, es, ANN_REG_READ,
                  ['Read %s: 0x%02X%s' % (name, raw, inc),
                   'R %s 0x%02X' % (name, raw),
                   '%s:%02X' % (_short_reg(name), raw)])
        self._ann(ss, es, ANN_DATA,
                  ['Temperature delta: %+d °C (1 digit/°C, relative)%s'
                   % (delta, note),
                   'ΔT %+d °C' % delta,
                   '%+d°C' % delta])
        self._emit_python(ss, es, 'REG_READ', (reg, bytes([raw])))
        self._emit_python(ss, es, 'TEMP', delta)

    def _emit_status(self, reg, name, raw):
        """Status registers get their own annotation row so the flags can be
        scanned as their own timeline."""
        ss, es = self.ss_block, self.es
        if reg == 0x07:
            flags = _flag_list(raw, STATUS_AUX_FIELDS)
        elif reg == 0x2F:
            flags = _flag_list(raw, FIFO_SRC_FIELDS)
            flags.append('FSS=%d' % (raw & 0x1F))
        elif reg in (0x31, 0x35):
            flags = _flag_list(raw, INT_SRC_FIELDS)
        elif reg == 0x39:
            flags = _flag_list(raw, CLICK_SRC_FIELDS)
        else:
            flags = _flag_list(raw, STATUS_FIELDS)
        text = ', '.join(flags) if flags else 'no flags set'
        self._ann(ss, es, ANN_STATUS,
                  ['%s: %s' % (name, text),
                   text,
                   flags[0][:8] if flags else '-'])
        self._emit_python(ss, es, 'STATUS', (reg, raw))
