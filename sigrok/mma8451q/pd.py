"""MMA8451Q sigrok protocol decoder.

Sits on top of the sigrok `i2c` decoder and annotates bus transactions with
the MMA8451Q register names and decoded field values. The chip is addressed
at 0x1C (SA0 = GND) or 0x1D (SA0 = VDDIO) and uses the plain register-pointer
protocol: write the register address, repeated START, read.

Annotation rows follow specs/sigrok_annotations.md: `data` (per-transaction
register content), `status` (discrete state/flag registers), `timing` (the
conformance-checked first-data-ready marker pair) and `warnings` (malformed
transactions). Data/status annotations carry three tiers (long, medium,
short); timing markers carry the fixed three-tier `'<check_name>: ...'`,
`'<check_name>'`, `'<glyph>'` shape; warnings carry a long message plus a
short tag.
"""

import sigrokdecode as srd

ADDRS = {0x1C, 0x1D}

# Register map. 0x00 has two names depending on F_MODE (FIFO enabled or not):
# STATUS when the FIFO is disabled, F_STATUS when F_MODE > 0.
REGS = {
    0x00: 'STATUS/F_STATUS',
    0x01: 'OUT_X_MSB',
    0x02: 'OUT_X_LSB',
    0x03: 'OUT_Y_MSB',
    0x04: 'OUT_Y_LSB',
    0x05: 'OUT_Z_MSB',
    0x06: 'OUT_Z_LSB',
    0x09: 'F_SETUP',
    0x0A: 'TRIG_CFG',
    0x0B: 'SYSMOD',
    0x0C: 'INT_SOURCE',
    0x0D: 'WHO_AM_I',
    0x0E: 'XYZ_DATA_CFG',
    0x0F: 'HP_FILTER_CUTOFF',
    0x10: 'PL_STATUS',
    0x11: 'PL_CFG',
    0x12: 'PL_COUNT',
    0x13: 'PL_BF_ZCOMP',
    0x14: 'P_L_THS_REG',
    0x15: 'FF_MT_CFG',
    0x16: 'FF_MT_SRC',
    0x17: 'FF_MT_THS',
    0x18: 'FF_MT_COUNT',
    0x1D: 'TRANSIENT_CFG',
    0x1E: 'TRANSIENT_SRC',
    0x1F: 'TRANSIENT_THS',
    0x20: 'TRANSIENT_COUNT',
    0x21: 'PULSE_CFG',
    0x22: 'PULSE_SRC',
    0x23: 'PULSE_THSX',
    0x24: 'PULSE_THSY',
    0x25: 'PULSE_THSZ',
    0x26: 'PULSE_TMLT',
    0x27: 'PULSE_LTCY',
    0x28: 'PULSE_WIND',
    0x29: 'ASLP_COUNT',
    0x2A: 'CTRL_REG1',
    0x2B: 'CTRL_REG2',
    0x2C: 'CTRL_REG3',
    0x2D: 'CTRL_REG4',
    0x2E: 'CTRL_REG5',
    0x2F: 'OFF_X',
    0x30: 'OFF_Y',
    0x31: 'OFF_Z',
}

# The two names 0x00 carries: STATUS while the FIFO is disabled (F_MODE = 0),
# F_STATUS while it is enabled (F_MODE > 0).
REG_STATUS = 0x00
REGS_STATUS = {REG_STATUS: 'STATUS'}
REGS_F_STATUS = {REG_STATUS: 'F_STATUS'}

# Registers that are read-only status/source registers (never written).
READ_ONLY_REGS = {
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
    0x0B, 0x0C, 0x0D, 0x10, 0x16, 0x1E, 0x22,
}

# Registers that are reserved by the datasheet (reads return 0, must not be
# written).
RESERVED_REGS = {
    0x07, 0x08, 0x19, 0x1A, 0x1B, 0x1C,
}
RESERVED_REGS |= set(range(0x32, 0x80))

# --- Human-readable enumerations -------------------------------------------

# CTRL_REG1 (0x2A) bits 7:6 - sleep-mode ODR.
ASLP_RATE = {0: '50 Hz', 1: '12.5 Hz', 2: '6.25 Hz', 3: '1.56 Hz'}

# CTRL_REG1 (0x2A) bits 5:3 - output data rate.
DR = {
    0: '800 Hz', 1: '400 Hz', 2: '200 Hz', 3: '100 Hz',
    4: '50 Hz', 5: '12.5 Hz', 6: '6.25 Hz', 7: '1.56 Hz',
}

# XYZ_DATA_CFG (0x0E) bits 1:0 - full-scale range.
FS = {0: '±2g', 1: '±4g', 2: '±8g', 3: 'reserved'}

# Counts per g for each full-scale range, 14-bit output.
FS_COUNTS_14BIT = {0: 4096.0, 1: 2048.0, 2: 1024.0}

# Counts per g for each full-scale range, 8-bit fast-read output.
FS_COUNTS_8BIT = {0: 64.0, 1: 32.0, 2: 16.0}

# CTRL_REG2 (0x2B) bits 1:0 (MODS) and 4:3 (SMODS) - oversampling mode.
MODS = {
    0: 'normal',
    1: 'low-noise low-power',
    2: 'high resolution',
    3: 'low power',
}

# F_SETUP (0x09) bits 7:6 - FIFO mode.
F_MODE = {
    0: 'disabled',
    1: 'circular',
    2: 'fill',
    3: 'trigger',
}

# SYSMOD (0x0B) bits 1:0 - system mode.
SYSMOD = {0: 'STANDBY', 1: 'WAKE', 2: 'SLEEP'}

# PL_STATUS (0x10) bits 2:1 - portrait / landscape orientation.
LAPO = {
    0: 'portrait up',
    1: 'portrait down',
    2: 'landscape right',
    3: 'landscape left',
}

# PL_BF_ZCOMP (0x13) bits 2:0 - Z-lock angle in degrees.
ZLOCK_DEG = {0: 14, 1: 18, 2: 21, 3: 25, 4: 29, 5: 33, 6: 37, 7: 42}

# P_L_THS_REG (0x14) bits 7:3 - portrait/landscape trip angle in degrees.
PL_THS_DEG = {
    0x07: 15, 0x09: 20, 0x0C: 30, 0x0D: 35, 0x0F: 40,
    0x10: 45, 0x13: 55, 0x14: 60, 0x17: 70, 0x19: 75,
}

# HP_FILTER_CUTOFF (0x0F) bits 1:0 - high-pass cutoff code.
HPF_SEL = {0: 'SEL0', 1: 'SEL1', 2: 'SEL2', 3: 'SEL3'}

# Threshold LSB: 0.063 g per code (FF_MT_THS, TRANSIENT_THS, PULSE_THSx).
THS_LSB_G = 0.063

# Offset registers: 2 mg per code.
OFF_LSB_G = 0.002

# WHO_AM_I (0x0D) expected value.
WHO_AM_I_VALUE = 0x1A

# Interrupt source bit positions (INT_SOURCE / CTRL_REG4 / CTRL_REG5).
SOURCE_DATA_READY  = 0x01
SOURCE_FF_MT       = 0x04
SOURCE_PULSE       = 0x08
SOURCE_ORIENTATION = 0x10
SOURCE_TRANSIENT   = 0x20
SOURCE_FIFO        = 0x40
SOURCE_AUTO_SLEEP  = 0x80

INT_SOURCE_FLAGS = [
    (SOURCE_AUTO_SLEEP,  'SRC_ASLP'),
    (SOURCE_FIFO,       'SRC_FIFO'),
    (SOURCE_TRANSIENT,  'SRC_TRANS'),
    (SOURCE_ORIENTATION, 'SRC_LNDPRT'),
    (SOURCE_PULSE,      'SRC_PULSE'),
    (SOURCE_FF_MT,      'SRC_FF_MT'),
    (SOURCE_DATA_READY, 'SRC_DRDY'),
]

# --- Annotation class ids ----------------------------------------------------

ANN_WRITE         = 0
ANN_READ          = 1
ANN_STATUS        = 2
ANN_WARNING       = 3
# Named start/end pair for the "first_data_ready" conformance check (see
# specs/accelerometer/mma8451q.md, Timing Constraints, and
# specs/accelerometer/mma8451q_timing.conf): a CTRL_REG1 (0x2A) write that
# sets ACTIVE = 1 marks active_start; the first STATUS (0x00) read that
# returns ZYXDR = 1 marks active_ready.
ANN_ACTIVE_START  = 4
ANN_ACTIVE_READY  = 5


def _flags(raw, fields):
    """Return the list of named flags set in raw, in the order of fields."""
    return [name for bit, name in fields if raw & bit]


def _signed16(msb, lsb):
    """Big-endian signed 16-bit value from an MSB/LSB register pair."""
    v = (msb << 8) | lsb
    return v - 0x10000 if v >= 0x8000 else v


def _signed8(v):
    """Signed 8-bit value."""
    return v - 0x100 if v >= 0x80 else v


def _signed14(msb, lsb):
    """14-bit right-aligned signed acceleration from an MSB/LSB pair.

    The 16-bit MSB:LSB pair holds the 14-bit sample left-justified; bits
    1:0 of the LSB register are always zero, so an arithmetic shift by two
    recovers the signed count.
    """
    raw16 = _signed16(msb, lsb)
    return raw16 >> 2


def _decode_status(raw):
    """STATUS (0x00) with F_MODE = 0 - data status flags."""
    fields = [
        (0x80, 'ZYXOW'),
        (0x40, 'ZOW'),
        (0x20, 'YOW'),
        (0x10, 'XOW'),
        (0x08, 'ZYXDR'),
        (0x04, 'ZDR'),
        (0x02, 'YDR'),
        (0x01, 'XDR'),
    ]
    flags = _flags(raw, fields)
    return 'STATUS 0x%02X: %s' % (raw, ', '.join(flags) if flags else 'none')


def _decode_f_status(raw):
    """F_STATUS (0x00) with F_MODE > 0 - FIFO status flags."""
    parts = []
    if raw & 0x80:
        parts.append('F_OVF')
    if raw & 0x40:
        parts.append('F_WMRK_FLAG')
    parts.append('F_CNT=%d' % (raw & 0x3F))
    return 'F_STATUS 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_f_setup(raw):
    """F_SETUP (0x09) - FIFO mode and watermark."""
    mode = (raw >> 6) & 0x03
    wmrk = raw & 0x3F
    return 'F_SETUP 0x%02X: F_MODE=%s, F_WMRK=%d' % (raw, F_MODE.get(mode, '?'), wmrk)


def _decode_trig_cfg(raw):
    """TRIG_CFG (0x0A) - FIFO trigger sources."""
    fields = [
        (0x20, 'Trig_TRANS'),
        (0x10, 'Trig_LNDPRT'),
        (0x08, 'Trig_PULSE'),
        (0x04, 'Trig_FF_MT'),
    ]
    flags = _flags(raw, fields)
    return 'TRIG_CFG 0x%02X: %s' % (raw, ', '.join(flags) if flags else 'none')


def _decode_sysmod(raw):
    """SYSMOD (0x0B) - FIFO gate error and system mode."""
    parts = []
    if raw & 0x80:
        parts.append('FGERR')
    parts.append('FGT=%d' % ((raw >> 2) & 0x1F))
    parts.append('SYSMOD=%s' % SYSMOD.get(raw & 0x03, '?'))
    return 'SYSMOD 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_int_source(raw):
    """INT_SOURCE (0x0C) - interrupt source status."""
    flags = _flags(raw, INT_SOURCE_FLAGS)
    return 'INT_SOURCE 0x%02X: %s' % (raw, ', '.join(flags) if flags else 'none')


def _decode_xyz_data_cfg(raw):
    """XYZ_DATA_CFG (0x0E) - high-pass output and full-scale range."""
    fs = raw & 0x03
    parts = []
    if raw & 0x10:
        parts.append('HPF_OUT')
    parts.append('FS=%s' % FS.get(fs, '?'))
    return 'XYZ_DATA_CFG 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_hp_filter_cutoff(raw):
    """HP_FILTER_CUTOFF (0x0F) - pulse-detector filters and HPF cutoff."""
    parts = []
    if raw & 0x20:
        parts.append('Pulse_HPF_BYP')
    if raw & 0x10:
        parts.append('Pulse_LPF_EN')
    parts.append('SEL=%s' % HPF_SEL.get(raw & 0x03, '?'))
    return 'HP_FILTER_CUTOFF 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_pl_status(raw):
    """PL_STATUS (0x10) - orientation detection status."""
    parts = []
    if raw & 0x80:
        parts.append('NEWLP')
    if raw & 0x40:
        parts.append('LO')
    parts.append('LAPO=%s' % LAPO.get((raw >> 1) & 0x03, '?'))
    parts.append('BAFRO=%s' % ('back' if (raw & 0x01) else 'front'))
    return 'PL_STATUS 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_pl_cfg(raw):
    """PL_CFG (0x11) - orientation detector enable and debounce mode."""
    parts = []
    parts.append('PL_EN=%d' % (1 if (raw & 0x40) else 0))
    parts.append('DBCNTM=%d' % (1 if (raw & 0x80) else 0))
    return 'PL_CFG 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_pl_bf_zcomp(raw):
    """PL_BF_ZCOMP (0x13) - back/front thresholds and Z-lock angle."""
    zlock = raw & 0x07
    return 'PL_BF_ZCOMP 0x%02X: BKFR=%d, ZLOCK=%d°' % (
        raw, (raw >> 6) & 0x03, ZLOCK_DEG.get(zlock, 0))


def _decode_pl_ths(raw):
    """P_L_THS_REG (0x14) - portrait/landscape trip angle and hysteresis."""
    ths_code = (raw >> 3) & 0x1F
    hys = raw & 0x07
    ths_deg = PL_THS_DEG.get(ths_code)
    ths_str = ('%d°' % ths_deg) if ths_deg is not None else ('code 0x%02X' % ths_code)
    return 'P_L_THS_REG 0x%02X: P_L_THS=%s, HYS=%d' % (raw, ths_str, hys)


def _decode_ff_mt_cfg(raw):
    """FF_MT_CFG (0x15) - freefall / motion detector configuration."""
    parts = []
    if raw & 0x80:
        parts.append('ELE')
    parts.append('OAE=%s' % ('motion' if (raw & 0x40) else 'freefall'))
    axes = []
    if raw & 0x20:
        axes.append('Z')
    if raw & 0x10:
        axes.append('Y')
    if raw & 0x08:
        axes.append('X')
    parts.append('EFE=%s' % (','.join(axes) if axes else 'none'))
    return 'FF_MT_CFG 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_ff_mt_src(raw):
    """FF_MT_SRC (0x16) - freefall / motion event flags."""
    fields = [
        (0x80, 'EA'),
        (0x20, 'ZHE'),
        (0x10, 'ZHP'),
        (0x08, 'YHE'),
        (0x04, 'YHP'),
        (0x02, 'XHE'),
        (0x01, 'XHP'),
    ]
    flags = _flags(raw, fields)
    return 'FF_MT_SRC 0x%02X: %s' % (raw, ', '.join(flags) if flags else 'none')


def _decode_ff_mt_ths(raw):
    """FF_MT_THS (0x17) - freefall / motion threshold."""
    ths = raw & 0x7F
    return 'FF_MT_THS 0x%02X: THS=%d (%.3f g), DBCNTM=%d' % (
        raw, ths, ths * THS_LSB_G, 1 if (raw & 0x80) else 0)


def _decode_transient_cfg(raw):
    """TRANSIENT_CFG (0x1D) - transient detector configuration."""
    parts = []
    if raw & 0x10:
        parts.append('ELE')
    axes = []
    if raw & 0x08:
        axes.append('Z')
    if raw & 0x04:
        axes.append('Y')
    if raw & 0x02:
        axes.append('X')
    parts.append('TEFE=%s' % (','.join(axes) if axes else 'none'))
    if raw & 0x01:
        parts.append('HPF_BYP')
    return 'TRANSIENT_CFG 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_transient_src(raw):
    """TRANSIENT_SRC (0x1E) - transient event flags."""
    fields = [
        (0x40, 'EA'),
        (0x20, 'ZTRANSE'),
        (0x10, 'Z_Trans_Pol'),
        (0x08, 'YTRANSE'),
        (0x04, 'Y_Trans_Pol'),
        (0x02, 'XTRANSE'),
        (0x01, 'X_Trans_Pol'),
    ]
    flags = _flags(raw, fields)
    return 'TRANSIENT_SRC 0x%02X: %s' % (raw, ', '.join(flags) if flags else 'none')


def _decode_transient_ths(raw):
    """TRANSIENT_THS (0x1F) - transient threshold."""
    ths = raw & 0x7F
    return 'TRANSIENT_THS 0x%02X: THS=%d (%.3f g), DBCNTM=%d' % (
        raw, ths, ths * THS_LSB_G, 1 if (raw & 0x80) else 0)


def _decode_pulse_cfg(raw):
    """PULSE_CFG (0x21) - tap (pulse) detector configuration."""
    parts = []
    if raw & 0x80:
        parts.append('DPA')
    if raw & 0x40:
        parts.append('ELE')
    dpe = []
    spe = []
    if raw & 0x20:
        dpe.append('Z')
    if raw & 0x10:
        spe.append('Z')
    if raw & 0x08:
        dpe.append('Y')
    if raw & 0x04:
        spe.append('Y')
    if raw & 0x02:
        dpe.append('X')
    if raw & 0x01:
        spe.append('X')
    parts.append('DPE=%s' % (','.join(dpe) if dpe else 'none'))
    parts.append('SPE=%s' % (','.join(spe) if spe else 'none'))
    return 'PULSE_CFG 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_pulse_src(raw):
    """PULSE_SRC (0x22) - tap event flags."""
    fields = [
        (0x80, 'EA'),
        (0x40, 'AxZ'),
        (0x20, 'AxY'),
        (0x10, 'AxX'),
        (0x08, 'DPE'),
        (0x04, 'PolZ'),
        (0x02, 'PolY'),
        (0x01, 'PolX'),
    ]
    flags = _flags(raw, fields)
    return 'PULSE_SRC 0x%02X: %s' % (raw, ', '.join(flags) if flags else 'none')


def _decode_pulse_ths(name, raw):
    """PULSE_THSx (0x23..0x25) - per-axis tap threshold."""
    ths = raw & 0x7F
    return '%s 0x%02X: THS=%d (%.3f g)' % (name, raw, ths, ths * THS_LSB_G)


def _decode_ctrl_reg1(raw):
    """CTRL_REG1 (0x2A) - data rate, oversampling and power mode."""
    parts = []
    parts.append('DR=%s' % DR.get((raw >> 3) & 0x07, '?'))
    parts.append('ASLP_RATE=%s' % ASLP_RATE.get((raw >> 6) & 0x03, '?'))
    if raw & 0x04:
        parts.append('LNOISE')
    if raw & 0x02:
        parts.append('F_READ')
    parts.append('ACTIVE=%d' % (1 if (raw & 0x01) else 0))
    return 'CTRL_REG1 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_ctrl_reg2(raw):
    """CTRL_REG2 (0x2B) - reset, self-test and oversampling mode."""
    parts = []
    if raw & 0x80:
        parts.append('ST')
    if raw & 0x40:
        parts.append('RST')
    parts.append('MODS=%s' % MODS.get(raw & 0x03, '?'))
    parts.append('SMODS=%s' % MODS.get((raw >> 3) & 0x03, '?'))
    if raw & 0x04:
        parts.append('SLPE')
    return 'CTRL_REG2 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_ctrl_reg3(raw):
    """CTRL_REG3 (0x2C) - FIFO gate, wake sources and interrupt pins."""
    parts = []
    if raw & 0x80:
        parts.append('FIFO_GATE')
    wake = []
    if raw & 0x40:
        wake.append('TRANS')
    if raw & 0x20:
        wake.append('LNDPRT')
    if raw & 0x10:
        wake.append('PULSE')
    if raw & 0x08:
        wake.append('FF_MT')
    parts.append('WAKE=%s' % (','.join(wake) if wake else 'none'))
    parts.append('IPOL=%s' % ('active-high' if (raw & 0x02) else 'active-low'))
    parts.append('PP_OD=%s' % ('open-drain' if (raw & 0x01) else 'push-pull'))
    return 'CTRL_REG3 0x%02X: %s' % (raw, ', '.join(parts))


def _decode_ctrl_reg4(raw):
    """CTRL_REG4 (0x2D) - interrupt enables."""
    enables = []
    for bit, name in (
        (0x80, 'ASLP'),
        (0x40, 'FIFO'),
        (0x20, 'TRANS'),
        (0x10, 'LNDPRT'),
        (0x08, 'PULSE'),
        (0x04, 'FF_MT'),
        (0x01, 'DRDY'),
    ):
        if raw & bit:
            enables.append('INT_EN_' + name)
    return 'CTRL_REG4 0x%02X: %s' % (raw, ', '.join(enables) if enables else 'none')


def _decode_ctrl_reg5(raw):
    """CTRL_REG5 (0x2E) - interrupt pin routing (1 = INT1, 0 = INT2)."""
    routes = []
    for bit, name in (
        (0x80, 'ASLP'),
        (0x40, 'FIFO'),
        (0x20, 'TRANS'),
        (0x10, 'LNDPRT'),
        (0x08, 'PULSE'),
        (0x04, 'FF_MT'),
        (0x01, 'DRDY'),
    ):
        if raw & bit:
            routes.append('INT_CFG_%s=%s' % (name, 'INT1' if (raw & bit) else 'INT2'))
    return 'CTRL_REG5 0x%02X: %s' % (raw, ', '.join(routes) if routes else 'none')


def _decode_offset(name, raw):
    """OFF_x (0x2F..0x31) - per-axis zero-g offset, 2 mg/LSB."""
    val = _signed8(raw)
    return '%s 0x%02X: %+d (%+.3f g)' % (name, raw, val, val * OFF_LSB_G)


def _decode_pl_count(name, raw):
    """PL_COUNT / FF_MT_COUNT / TRANSIENT_COUNT (0x12, 0x18, 0x20)."""
    return '%s 0x%02X: %d' % (name, raw, raw)


def _decode_pulse_time(name, raw):
    """PULSE_TMLT / PULSE_LTCY / PULSE_WIND (0x26..0x28)."""
    return '%s 0x%02X: %d' % (name, raw, raw)


def _decode_aslp_count(raw):
    """ASLP_COUNT (0x29) - Auto-SLEEP inactivity time."""
    return 'ASLP_COUNT 0x%02X: %d' % (raw, raw)


def _decode_axis_pair(name, msb, lsb, counts_per_g):
    """Decode one 14-bit axis sample into counts and g."""
    counts = _signed14(msb, lsb)
    return '%s=%+d (%+.4f g)' % (name, counts, counts / counts_per_g)


def _decode_burst(buf, range_code, fast_read):
    """Decode the 0x01..0x06 data burst into per-axis g values.

    In 14-bit mode (F_READ = 0) the burst holds six bytes: MSB:LSB pairs
    for X, Y and Z. In 8-bit fast-read mode only the three MSB registers
    carry data (the auto-increment skips the LSB registers).
    """
    if fast_read:
        if len(buf) < 3:
            return 'Short burst %dB' % len(buf)
        scale = FS_COUNTS_8BIT.get(range_code, 64.0)
        parts = []
        for name, msb in (('X', buf[0]), ('Y', buf[1]), ('Z', buf[2])):
            counts = _signed8(msb)
            parts.append('%s=%+d (%+.4f g)' % (name, counts, counts / scale))
        return 'Burst (8-bit): %s' % ', '.join(parts)
    if len(buf) < 6:
        return 'Short burst %dB' % len(buf)
    scale = FS_COUNTS_14BIT.get(range_code, 4096.0)
    parts = [
        _decode_axis_pair('X', buf[0], buf[1], scale),
        _decode_axis_pair('Y', buf[2], buf[3], scale),
        _decode_axis_pair('Z', buf[4], buf[5], scale),
    ]
    return 'Burst (14-bit): %s' % ', '.join(parts)


def _warn_tag(msg):
    """Short (<= 5 char) tag for a warning message, used as the narrow-zoom tier."""
    m = msg.lower()
    if 'who_am_i' in m or 'device id' in m or 'identity' in m:
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
    id = 'mma8451q'
    name = 'MMA8451Q'
    longname = 'MMA8451Q 3-axis MEMS accelerometer (NXP Semiconductors)'
    desc = 'Decode MMA8451Q I2C accelerometer register transactions.'
    license = 'gplv2+'
    inputs = ['i2c']
    outputs = ['mma8451q']
    tags = ['IC', 'Sensor']

    annotations = (
        ('reg-write',   'Register write'),
        ('reg-read',    'Register read'),
        ('status',      'Status flags'),
        ('warning',     'Warning'),
        ('active-start', 'First data ready: ACTIVE set'),
        ('active-ready', 'First data ready: STATUS reports ZYXDR'),
    )
    annotation_rows = (
        ('data',     'Data',     (ANN_WRITE, ANN_READ)),
        ('status',   'Status',   (ANN_STATUS,)),
        ('timing',   'Timing',   (ANN_ACTIVE_START, ANN_ACTIVE_READY)),
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
        """OUTPUT_PYTHON mirror of each transaction-level data annotation:
        ('REG_READ' | 'REG_WRITE', (register, bytes)) - the chip uses the
        plain register-pointer protocol, so the register is always known."""
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
        # Last-seen configuration, used to decode the data registers.
        self.range_code = 0x00   # XYZ_DATA_CFG FS<1:0>, default ±2g
        self.fifo_mode = 0x00    # F_SETUP F_MODE<1:0>, default disabled
        self.fast_read = False   # CTRL_REG1 F_READ, default 14-bit output
        self.active = False      # CTRL_REG1 ACTIVE
        self._warned_addr = None  # address already warned about this transaction

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)
        self.out_python = self.register(srd.OUTPUT_PYTHON)

    def _warn(self, ss, es, msg):
        self.put(ss, es, self.out_ann, [ANN_WARNING, [msg, _warn_tag(msg)]])

    def _emit_status(self, reg, reg_name, desc):
        """Emit the status-row annotation for a status/source register."""
        short = desc.split(': ', 1)[1] if ': ' in desc else desc
        self.put(self.ss_block, self.es, self.out_ann,
                 [ANN_STATUS, [desc, short, reg_name[:8]]])

    def _emit_active_start(self):
        """active_start: a CTRL_REG1 write that sets ACTIVE = 1.

        Tier 0 carries the conformance check name (`first_data_ready`,
        see specs/accelerometer/mma8451q_timing.conf) verbatim so a
        headless sigrok-cli decode can match on it, plus the marker name
        the chip spec names (`active_start`). Tier 1 is the bare check
        name, tier 2 the compact glyph (PulseView-only)."""
        self.put(self.ss_block, self.es, self.out_ann,
                 [ANN_ACTIVE_START,
                  ['first_data_ready: active_start - ACTIVE set (CTRL_REG1 0x2A write)',
                   'first_data_ready',
                   '\u25b6ACT']])

    def _emit_active_ready(self):
        """active_ready: the first STATUS read reporting ZYXDR = 1.

        Shaped like _emit_active_start(): tier 0 carries the conformance
        check name (`first_data_ready`) and the marker name
        (`active_ready`), tier 1 is the bare check name, tier 2 the glyph."""
        self.put(self.ss_block, self.es, self.out_ann,
                 [ANN_ACTIVE_READY,
                  ['first_data_ready: active_ready - STATUS 0x00 reports ZYXDR (first sample ready)',
                   'first_data_ready',
                   'ACT\u2713']])

    def _status_read(self, reg, raw):
        """Decode and emit a status/source register read."""
        if reg == REG_STATUS:
            if self.fifo_mode:
                desc = _decode_f_status(raw)
                reg_name = REGS_F_STATUS[REG_STATUS]
            else:
                desc = _decode_status(raw)
                reg_name = REGS_STATUS[REG_STATUS]
            self._emit_status(reg, reg_name, desc)
            # active_ready: first STATUS read with ZYXDR = 1 (F_MODE = 0).
            if not self.fifo_mode and (raw & 0x08):
                self._emit_active_ready()
            return desc
        if reg == 0x0B:
            desc = _decode_sysmod(raw)
        elif reg == 0x0C:
            desc = _decode_int_source(raw)
        elif reg == 0x10:
            desc = _decode_pl_status(raw)
        elif reg == 0x16:
            desc = _decode_ff_mt_src(raw)
        elif reg == 0x1E:
            desc = _decode_transient_src(raw)
        elif reg == 0x22:
            desc = _decode_pulse_src(raw)
        else:
            return None
        self._emit_status(reg, REGS.get(reg, '?'), desc)
        return desc

    def _config_read(self, reg, raw):
        """Decode a configuration register read."""
        if reg == 0x09:
            return _decode_f_setup(raw)
        if reg == 0x0A:
            return _decode_trig_cfg(raw)
        if reg == 0x0E:
            return _decode_xyz_data_cfg(raw)
        if reg == 0x0F:
            return _decode_hp_filter_cutoff(raw)
        if reg == 0x11:
            return _decode_pl_cfg(raw)
        if reg == 0x13:
            return _decode_pl_bf_zcomp(raw)
        if reg == 0x14:
            return _decode_pl_ths(raw)
        if reg == 0x15:
            return _decode_ff_mt_cfg(raw)
        if reg == 0x17:
            return _decode_ff_mt_ths(raw)
        if reg == 0x1D:
            return _decode_transient_cfg(raw)
        if reg == 0x1F:
            return _decode_transient_ths(raw)
        if reg == 0x21:
            return _decode_pulse_cfg(raw)
        if reg == 0x2A:
            return _decode_ctrl_reg1(raw)
        if reg == 0x2B:
            return _decode_ctrl_reg2(raw)
        if reg == 0x2C:
            return _decode_ctrl_reg3(raw)
        if reg == 0x2D:
            return _decode_ctrl_reg4(raw)
        if reg == 0x2E:
            return _decode_ctrl_reg5(raw)
        if reg in (0x2F, 0x30, 0x31):
            return _decode_offset(REGS[reg], raw)
        if reg in (0x12, 0x18, 0x20):
            return _decode_pl_count(REGS[reg], raw)
        if reg in (0x26, 0x27, 0x28):
            return _decode_pulse_time(REGS[reg], raw)
        if reg == 0x29:
            return _decode_aslp_count(raw)
        if reg in (0x23, 0x24, 0x25):
            return _decode_pulse_ths(REGS[reg], raw)
        return None

    def _note_config_write(self, reg, raw):
        """Update the cached configuration after a configuration write.

        The cached values drive how the data registers are decoded: FS sets
        the counts/g scale, F_MODE selects the STATUS vs F_STATUS
        interpretation of 0x00, and F_READ selects 8-bit vs 14-bit output.
        """
        if reg == 0x0E:
            self.range_code = raw & 0x03
        elif reg == 0x09:
            self.fifo_mode = (raw >> 6) & 0x03
        elif reg == 0x2A:
            self.fast_read = bool(raw & 0x02)
            self.active = bool(raw & 0x01)

    def _finish_transaction(self):
        if self.state not in ('GET_DATA_WRITE', 'GET_DATA_READ', 'GET_REG_PTR'):
            return

        reg  = self.reg_ptr
        name = REGS.get(reg, 'Reg[0x%02X]' % reg) if reg is not None else '?'

        if self.is_read:
            if reg == 0x0D and len(self.databuf) == 1:
                val = self.databuf[0]
                ok = ' (MMA8451Q ✓)' if val == WHO_AM_I_VALUE else ' (expected 0x1A!)'
                if val != WHO_AM_I_VALUE:
                    self._warn(self.ss_block, self.es,
                               'WHO_AM_I mismatch: read 0x%02X, expected 0x%02X'
                               % (val, WHO_AM_I_VALUE))
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['WHO_AM_I 0x%02X%s' % (val, ok),
                           'ID 0x%02X' % val,
                           'ID']])
                return
            if reg == 0x01 and len(self.databuf) in (3, 6):
                desc = _decode_burst(self.databuf, self.range_code, self.fast_read)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          [desc,
                           'Burst %dB' % len(self.databuf),
                           'ACC']])
                return
            if len(self.databuf) == 1:
                val = self.databuf[0]
                desc = self._status_read(reg, val)
                if desc is None:
                    desc = self._config_read(reg, val)
                if desc is None:
                    desc = 'Read %s: 0x%02X' % (name, val)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          [desc,
                           'R %s 0x%02X' % (name, val),
                           name[:8]]])
                return
            if self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_READ,
                          ['Read %s: %d bytes' % (name, len(self.databuf)),
                           'R %s %dB' % (name, len(self.databuf)),
                           'R %s' % name[:6]]])
                self._warn(self.ss_block, self.es,
                           'Unexpected read length %d for %s' % (len(self.databuf), name))
        else:
            if not self.databuf:
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE,
                          ['Pointer → %s (0x%02X)' % (name, reg),
                           'PTR 0x%02X' % reg,
                           'PTR']])
                return
            if len(self.databuf) == 1:
                val = self.databuf[0]
                desc = self._config_read(reg, val)
                if desc is None:
                    desc = 'Write %s: 0x%02X' % (name, val)
                self._note_config_write(reg, val)
                self.put(self.ss_block, self.es, self.out_ann,
                         [ANN_WRITE,
                          [desc,
                           'W %s 0x%02X' % (name, val),
                           name[:8]]])
                # Protocol violations: writing a read-only or a reserved
                # register is not allowed by the datasheet.
                if reg in READ_ONLY_REGS:
                    self._warn(self.ss_block, self.es,
                               'Write to read-only register %s (0x%02X)' % (name, reg))
                elif reg in RESERVED_REGS:
                    self._warn(self.ss_block, self.es,
                               'Write to reserved register 0x%02X' % reg)
                # active_start: a CTRL_REG1 write that sets ACTIVE = 1.
                if reg == 0x2A and (val & 0x01):
                    self._emit_active_start()
                return
            if self.databuf:
                self._warn(self.ss_block, self.es,
                           'Unexpected write length %d for %s' % (len(self.databuf), name))

    def decode(self, ss, es, data):
        ptype, pdata = data
        self.ss, self.es = ss, es

        if ptype in ('START', 'START REPEAT'):
            if ptype == 'START REPEAT' and self.state in ('GET_REG_PTR', 'IDLE'):
                # Repeated START inside one transaction: keep the register
                # pointer and the already-warned address.
                pass
            else:
                self._finish_transaction()
                self.databuf  = []
                self.is_read  = False
                self._warned_addr = None
            self.ss_block = ss
            self.state    = 'GET_ADDR'

        elif ptype in ('ADDRESS READ', 'ADDRESS WRITE'):
            if pdata not in ADDRS:
                # A register read emits an ADDRESS WRITE (pointer phase)
                # followed by an ADDRESS READ (data phase) for the same
                # device - warn once per transaction, not twice.
                if self._warned_addr != pdata:
                    self._warned_addr = pdata
                    self._warn(ss, es, 'Unknown I2C address 0x%02X' % pdata)
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
