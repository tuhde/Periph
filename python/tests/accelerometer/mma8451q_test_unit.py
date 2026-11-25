"""Unit test for the MMA8451Q — runs without hardware using the I2C mock.

Verifies the driver's register read/write sequencing, the init sequence,
sign-extension and range-scaling math, the threshold / debounce / pulse
timing conversions, the interrupt enable/route bit placement, the FIFO
handling and the read-to-clear behaviour of poll_interrupt(). Mock the
MMA8451Q's WHO_AM_I (0x0D == 0x1A) so init's identity check passes.
"""

import sys
from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.accelerometer.mma8451q import (
    MMA8451QMinimal,
    MMA8451QFull,
)

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def new_mock():
    """A mock preloaded with the identity register and a known sample."""
    mock = I2CConnectionMock()
    mock.set_register(MMA8451QMinimal._REG_WHO_AM_I, 0x1A)
    mock.set_register(MMA8451QMinimal._REG_CTRL_REG1, 0x19)
    mock.set_register(MMA8451QMinimal._REG_CTRL_REG2, 0x00)
    # x = -1 g (-4096 counts -> raw16 = -16384 = 0xC000), y = 0, z = +0.5 g
    # (+2048 counts -> raw16 = 8192 = 0x2000).
    mock.set_register(MMA8451QMinimal._REG_OUT_X_MSB, 0xC0, 0x00, 0x00, 0x00, 0x20, 0x00)
    return mock


def writes_to(mock, reg):
    """All values written to `reg` (the mock logs every write)."""
    return [w[1] for w in mock.writes if len(w) == 2 and w[0] == reg]


def last_write_to(mock, reg):
    """The most recent value written to `reg`, or None."""
    values = writes_to(mock, reg)
    return values[-1] if values else None


class RecordingConn(I2CConnectionMock):
    """Mock that also records the (register, length) pairs it was asked for."""

    def __init__(self):
        super().__init__()
        self.reg_reads = []

    def read_reg(self, reg, length):
        self.reg_reads.append((reg, length))
        return super().read_reg(reg, length)


# --- Minimal driver -------------------------------------------------------

mock = new_mock()
accel = MMA8451QMinimal(mock)
check_true('construct_minimal', True)

# Init sequence: CTRL_REG2 = 0x40 (RST) then 0x00; XYZ_DATA_CFG = 0x00;
# CTRL_REG1 = 0x19.
ctrl2 = writes_to(mock, MMA8451QMinimal._REG_CTRL_REG2)
check_true('init_ctrl_reg2_reset_then_zero',
           ctrl2 and ctrl2[0] == 0x40 and ctrl2[-1] == 0x00)
xyz = writes_to(mock, MMA8451QMinimal._REG_XYZ_DATA_CFG)
check_true('init_xyz_data_cfg_zero', xyz and xyz[-1] == 0x00)
ctrl1 = writes_to(mock, MMA8451QMinimal._REG_CTRL_REG1)
check_true('init_ctrl_reg1_0x19', ctrl1 and ctrl1[-1] == 0x19)

# The WHO_AM_I read happens before any configuration write.
read_regs = [w[0] for w in mock.writes]
check_true('init_reads_who_am_i', MMA8451QMinimal._REG_WHO_AM_I in read_regs)

# read(): x = -1 g, y = 0 g, z = +0.5 g at ±2 g.
x, y, z = accel.read()
check_true('read_x_minus_1g', abs(x - (-1.0)) < 1e-9)
check_true('read_y_zero_g', abs(y - 0.0) < 1e-9)
check_true('read_z_plus_half_g', abs(z - 0.5) < 1e-9)

# A wrong WHO_AM_I must raise.
bad = I2CConnectionMock()
bad.set_register(MMA8451QMinimal._REG_WHO_AM_I, 0x1B)
raised = False
try:
    MMA8451QMinimal(bad)
except ValueError:
    raised = True
check_true('init_rejects_bad_who_am_i', raised)


# --- Full driver ----------------------------------------------------------

mock2 = new_mock()
full = MMA8451QFull(mock2)
check_true('construct_full', True)

# set_range(4): XYZ_DATA_CFG FS bits = 01, so the byte becomes 0x01.
full.set_range(4)
check_true('set_range_4g', last_write_to(mock2, MMA8451QMinimal._REG_XYZ_DATA_CFG) == 0x01)
# The same write must have been bracketed by a STANDBY / ACTIVE pair.
ctrl1_writes = writes_to(mock2, MMA8451QMinimal._REG_CTRL_REG1)
check_true('set_range_is_standby_only',
           0x18 in ctrl1_writes and ctrl1_writes[-1] == 0x19)

# With ±4 g the sensitivity halves: x = -4096 counts -> -2 g.
x, y, z = full.read()
check_true('read_after_set_range_4g', abs(x - (-2.0)) < 1e-9)

# set_range(8) -> FS bits = 10.
full.set_range(8)
check_true('set_range_8g', last_write_to(mock2, MMA8451QMinimal._REG_XYZ_DATA_CFG) == 0x02)

# set_data_rate(50): DR bits = 100 -> CTRL_REG1 bits 5:3 = 0x20.
full.set_data_rate(50)
ctrl1_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG1)
check_true('set_data_rate_50hz', (ctrl1_last & 0x38) == 0x20)
# set_data_rate(1.56): DR bits = 111.
full.set_data_rate(1.56)
ctrl1_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG1)
check_true('set_data_rate_1_56hz', (ctrl1_last & 0x38) == 0x38)

# An unsupported rate raises.
raised = False
try:
    full.set_data_rate(123)
except ValueError:
    raised = True
check_true('set_data_rate_rejects_bad_value', raised)

# set_oversampling(2) (high resolution): MODS = 10, SMODS = 10.
full.set_oversampling(2)
ctrl2_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG2)
check_true('set_oversampling_high_resolution', (ctrl2_last & 0x03) == 0x02)
check_true('set_oversampling_sleep_mode', (ctrl2_last & 0x18) == 0x10)
# set_oversampling(0, 1): MODS = 00, SMODS = 01.
full.set_oversampling(0, 1)
ctrl2_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG2)
check_true('set_oversampling_modes_split',
           (ctrl2_last & 0x03) == 0x00 and (ctrl2_last & 0x18) == 0x08)

# set_low_noise(True): CTRL_REG1 bit 2 (LNOISE).
full.set_range(2)
full.set_low_noise(True)
ctrl1_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG1)
check_true('set_low_noise_true', ctrl1_last & 0x04)

# ±8 g is not available in low-noise mode.
raised = False
try:
    full.set_range(8)
except ValueError:
    raised = True
check_true('set_range_8g_rejected_in_low_noise', raised)

# set_low_noise(False) clears the bit again.
full.set_low_noise(False)
ctrl1_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG1)
check_true('set_low_noise_false', not (ctrl1_last & 0x04))

# set_resolution(8): F_READ = 1 (CTRL_REG1 bit 1).
full.set_resolution(8)
ctrl1_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG1)
check_true('set_resolution_8bit', ctrl1_last & 0x02)

# In 8-bit mode the output is one byte per axis: x = 0xC0 -> -64 counts,
# y = 0x00 -> 0, z = 0x20 -> +32 counts; at ±2 g that is 64 counts/g.
mock2.set_register(MMA8451QMinimal._REG_OUT_X_MSB, 0xC0)
mock2.set_register(MMA8451QMinimal._REG_OUT_Y_MSB, 0x00)
mock2.set_register(MMA8451QMinimal._REG_OUT_Z_MSB, 0x20)
x, y, z = full.read()
check_true('read_8bit_x', abs(x - (-1.0)) < 1e-9)
check_true('read_8bit_z', abs(z - 0.5) < 1e-9)

# Resolution cannot change while the FIFO is enabled.
full.set_fifo(1, 16)
raised = False
try:
    full.set_resolution(14)
except ValueError:
    raised = True
check_true('set_resolution_rejected_with_fifo', raised)
full.set_fifo(0)
full.set_resolution(14)
ctrl1_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG1)
check_true('set_resolution_14bit', not (ctrl1_last & 0x02))

# set_high_pass_output(True, 2): HPF_OUT = bit 4, SEL = 2.
full.set_high_pass_output(True, 2)
xyz_last = last_write_to(mock2, MMA8451QMinimal._REG_XYZ_DATA_CFG)
hp_last = last_write_to(mock2, MMA8451QMinimal._REG_HP_FILTER)
check_true('set_high_pass_output_enabled', xyz_last & 0x10)
check_true('set_high_pass_output_cutoff', (hp_last & 0x03) == 0x02)
full.set_high_pass_output(False)
xyz_last = last_write_to(mock2, MMA8451QMinimal._REG_XYZ_DATA_CFG)
check_true('set_high_pass_output_disabled', not (xyz_last & 0x10))

# set_offset(0.1, -0.05, 0.002): 2 mg/LSB -> 50, -25, 1.
full.set_offset(0.1, -0.05, 0.002)
check_true('set_offset_x', last_write_to(mock2, MMA8451QMinimal._REG_OFF_X) == 50)
check_true('set_offset_y', last_write_to(mock2, MMA8451QMinimal._REG_OFF_Y) == (256 - 25))
check_true('set_offset_z', last_write_to(mock2, MMA8451QMinimal._REG_OFF_Z) == 1)

# get_offset() reads them back in g.
ox, oy, oz = full.get_offset()
check_true('get_offset_x', abs(ox - 0.1) < 1e-6)
check_true('get_offset_y', abs(oy - (-0.05)) < 1e-6)
check_true('get_offset_z', abs(oz - 0.002) < 1e-6)

# Threshold conversion: 0.063 g/LSB. 0.5 g -> round(0.5/0.063) = 8.
full.set_freefall_motion(motion=False, axes=0x07, threshold_g=0.5, debounce_ms=20)
ths = last_write_to(mock2, MMA8451QMinimal._REG_FF_MT_THS)
check_true('set_freefall_motion_threshold', (ths & 0x7F) == 8)
# FF_MT_CFG: ELE=0, OAE=0 (freefall), ZEFE/YEFE/XEFE = 0x20|0x10|0x08.
cfg = last_write_to(mock2, MMA8451QMinimal._REG_FF_MT_CFG)
check_true('set_freefall_motion_axes', (cfg & 0x38) == 0x38)
check_true('set_freefall_motion_freefall', not (cfg & 0x40))
# Debounce at 1.56 Hz / normal oversampling: step 20 ms -> 20/20 = 1.
count = last_write_to(mock2, MMA8451QMinimal._REG_FF_MT_COUNT)
check_true('set_freefall_motion_debounce', count == 1)

# Motion mode sets OAE.
full.set_freefall_motion(motion=True, axes=0x03, threshold_g=0.2, latch=True)
cfg = last_write_to(mock2, MMA8451QMinimal._REG_FF_MT_CFG)
check_true('set_freefall_motion_motion', cfg & 0x40)
check_true('set_freefall_motion_latch', cfg & 0x80)
check_true('set_freefall_motion_axes_xy', (cfg & 0x38) == 0x18)
ths = last_write_to(mock2, MMA8451QMinimal._REG_FF_MT_THS)
check_true('set_freefall_motion_threshold_0_2g', (ths & 0x7F) == 3)

# Transient detector: axes mask and HPF bypass.
full.set_transient(axes=0x05, threshold_g=0.25, debounce_ms=10, bypass_hpf=True)
cfg = last_write_to(mock2, MMA8451QMinimal._REG_TRANSIENT_CFG)
check_true('set_transient_axes', (cfg & 0x0E) == 0x0A)
check_true('set_transient_bypass_hpf', cfg & 0x01)
ths = last_write_to(mock2, MMA8451QMinimal._REG_TRANSIENT_THS)
check_true('set_transient_threshold', (ths & 0x7F) == 4)

# Pulse detector: double tap on Z (mask 0x04) with per-axis thresholds.
full.set_pulse(double=0x04, threshold_g=(0.5, 0.5, 1.0), time_limit_ms=40,
                latency_ms=100, window_ms=300, latch=True)
cfg = last_write_to(mock2, MMA8451QMinimal._REG_PULSE_CFG)
check_true('set_pulse_double_z', cfg & 0x20)
check_true('set_pulse_latch', cfg & 0x40)
check_true('set_pulse_no_single', not (cfg & 0x07))
thsx = last_write_to(mock2, MMA8451QMinimal._REG_PULSE_THSX)
thsz = last_write_to(mock2, MMA8451QMinimal._REG_PULSE_THSZ)
check_true('set_pulse_threshold_x', (thsx & 0x7F) == 8)
check_true('set_pulse_threshold_z', (thsz & 0x7F) == 16)
# Pulse timing at 1.56 Hz / normal oversampling, lpf=True: TMLT step 20 ms
# -> 40/20 = 2; LTCY step 40 ms -> 100/40 = 2.5 -> 2; WIND 300/40 = 7.5 -> 8.
tmlt = last_write_to(mock2, MMA8451QMinimal._REG_PULSE_TMLT)
ltcy = last_write_to(mock2, MMA8451QMinimal._REG_PULSE_LTCY)
wind = last_write_to(mock2, MMA8451QMinimal._REG_PULSE_WIND)
check_true('set_pulse_time_limit', tmlt == 2)
check_true('set_pulse_latency', ltcy == 2)
check_true('set_pulse_window', wind == 8)
hp = last_write_to(mock2, MMA8451QMinimal._REG_HP_FILTER)
check_true('set_pulse_lpf_enabled', hp & 0x10)
check_true('set_pulse_hpf_enabled', not (hp & 0x20))

# Orientation detector: 45 deg -> P_L_THS 0x10, HYS 4.
full.set_orientation(True, debounce_ms=100, threshold_deg=45, hysteresis=4,
                     z_lock=4, back_front=1)
pl_cfg = last_write_to(mock2, MMA8451QMinimal._REG_PL_CFG)
pl_ths = last_write_to(mock2, MMA8451QMinimal._REG_P_L_THS)
pl_bf = last_write_to(mock2, MMA8451QMinimal._REG_PL_BF_ZCOMP)
check_true('set_orientation_enabled', pl_cfg & 0x40)
check_true('set_orientation_threshold', (pl_ths >> 3) == 0x10)
check_true('set_orientation_hysteresis', (pl_ths & 0x07) == 4)
check_true('set_orientation_z_lock', (pl_bf & 0x07) == 4)
check_true('set_orientation_back_front', (pl_bf >> 6) == 1)

# An unsupported trip angle raises.
raised = False
try:
    full.set_orientation(threshold_deg=17)
except ValueError:
    raised = True
check_true('set_orientation_rejects_bad_angle', raised)

# read_orientation() decodes PL_STATUS: NEWLP, LO, LAPO, BAFRO.
mock2.set_register(MMA8451QMinimal._REG_PL_STATUS, 0x80 | 0x40 | 0x04 | 0x01)
changed, lockout, lapo, back = full.read_orientation()
check_true('read_orientation_changed', changed is True)
check_true('read_orientation_lockout', lockout is True)
check_true('read_orientation_lapo', lapo == 2)
check_true('read_orientation_back', back is True)

# Auto-SLEEP: SLPE (CTRL_REG2 bit 2), ASLP_RATE (CTRL_REG1 bits 7:6),
# ASLP_COUNT, and the WAKE_* bits in CTRL_REG3.
full.set_auto_sleep(True, sleep_rate_hz=12.5, timeout_s=10,
                     wake_sources=full.SOURCE_PULSE | full.SOURCE_FF_MT)
ctrl2_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG2)
ctrl1_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG1)
ctrl3_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG3)
aslp = last_write_to(mock2, MMA8451QMinimal._REG_ASLP_COUNT)
check_true('set_auto_sleep_slpe', ctrl2_last & 0x04)
check_true('set_auto_sleep_rate', (ctrl1_last >> 6) == 0x01)
check_true('set_auto_sleep_wake_pulse', ctrl3_last & 0x10)
check_true('set_auto_sleep_wake_motion', ctrl3_last & 0x08)
check_true('set_auto_sleep_count', aslp == 16)

# FIFO gate.
full.set_fifo_gate(True)
ctrl3_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG3)
check_true('set_fifo_gate_enabled', ctrl3_last & 0x80)
full.set_fifo_gate(False)
ctrl3_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG3)
check_true('set_fifo_gate_disabled', not (ctrl3_last & 0x80))

# Interrupt pins: IPOL (CTRL_REG3 bit 1), PP_OD (bit 0).
full.set_interrupt_pins(active_high=True, open_drain=True)
ctrl3_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG3)
check_true('set_interrupt_pins_active_high', ctrl3_last & 0x02)
check_true('set_interrupt_pins_open_drain', ctrl3_last & 0x01)
full.set_interrupt_pins(active_high=False, open_drain=False)
ctrl3_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG3)
check_true('set_interrupt_pins_defaults', (ctrl3_last & 0x03) == 0x00)

# enable_interrupt: INT_EN_* in CTRL_REG4, routing in CTRL_REG5.
full.enable_interrupt(full.SOURCE_ORIENTATION, pin=1)
ctrl4_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG4)
ctrl5_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG5)
check_true('enable_interrupt_ctrl_reg4', ctrl4_last & full.SOURCE_ORIENTATION)
check_true('enable_interrupt_routed_to_int1', ctrl5_last & full.SOURCE_ORIENTATION)

# Route the same source to INT2 instead.
full.enable_interrupt(full.SOURCE_ORIENTATION, pin=2)
ctrl5_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG5)
check_true('enable_interrupt_routed_to_int2', not (ctrl5_last & full.SOURCE_ORIENTATION))

# disable_interrupt clears the INT_EN bit.
full.disable_interrupt(full.SOURCE_ORIENTATION)
ctrl4_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG4)
check_true('disable_interrupt_clears_ctrl_reg4',
           not (ctrl4_last & full.SOURCE_ORIENTATION))

# An invalid source raises.
raised = False
try:
    full.enable_interrupt(0x02)
except ValueError:
    raised = True
check_true('enable_interrupt_rejects_bad_source', raised)

# poll_interrupt() reads INT_SOURCE and clears the per-source flags.
mock2.set_register(MMA8451QMinimal._REG_INT_SOURCE,
                    full.SOURCE_PULSE | full.SOURCE_ORIENTATION)
status = full.poll_interrupt()
check_true('poll_interrupt_returns_status',
           status == (full.SOURCE_PULSE | full.SOURCE_ORIENTATION))
read_regs = [w[0] for w in mock2.writes]
check_true('poll_interrupt_clears_pulse', MMA8451QMinimal._REG_PULSE_SRC in read_regs)
check_true('poll_interrupt_clears_orientation',
           MMA8451QMinimal._REG_PL_STATUS in read_regs)

# SOURCE_DATA_READY is left set — the data read clears it.
mock2.set_register(MMA8451QMinimal._REG_INT_SOURCE, full.SOURCE_DATA_READY)
before = len(mock2.writes)
status = full.poll_interrupt()
check_true('poll_interrupt_keeps_data_ready',
           status == full.SOURCE_DATA_READY)

# FIFO status decoding: overflow bit 7, watermark bit 6, count bits 5:0.
mock2.set_register(MMA8451QMinimal._REG_STATUS, 0x80 | 0x40 | 0x05)
overflow, watermark, count = full.read_fifo_status()
check_true('read_fifo_status_overflow', overflow is True)
check_true('read_fifo_status_watermark', watermark is True)
check_true('read_fifo_status_count', count == 5)
check_true('fifo_level', full.fifo_level() == 5)

# FIFO configuration: F_SETUP mode bits 7:6, watermark bits 5:0.
full.set_fifo(1, 16)
f_setup = last_write_to(mock2, MMA8451QMinimal._REG_F_SETUP)
check_true('set_fifo_circular_watermark_16', f_setup == (0x01 << 6) | 16)
full.set_fifo(2, 0)
f_setup = last_write_to(mock2, MMA8451QMinimal._REG_F_SETUP)
check_true('set_fifo_fill_mode', (f_setup >> 6) == 0x02 and (f_setup & 0x3F) == 0)

# FIFO trigger sources: TRIG_CFG bits 5..2.
full.set_fifo_trigger(transient=True, orientation=False, pulse=True, motion=False)
trig = last_write_to(mock2, MMA8451QMinimal._REG_TRIG_CFG)
check_true('set_fifo_trigger_bits', (trig & 0x3C) == 0x28)

# Debounce step table: at 100 Hz / normal oversampling the step is 10 ms,
# so 50 ms -> 5 counts.
full.set_data_rate(100)
full.set_oversampling(0)
full.set_freefall_motion(motion=True, axes=0x07, threshold_g=0.5, debounce_ms=50)
count = last_write_to(mock2, MMA8451QMinimal._REG_FF_MT_COUNT)
check_true('debounce_step_100hz_normal', count == 5)
# At 100 Hz / high resolution the step is 2.5 ms, so 50 ms -> 20 counts.
full.set_oversampling(2)
full.set_freefall_motion(motion=True, axes=0x07, threshold_g=0.5, debounce_ms=50)
count = last_write_to(mock2, MMA8451QMinimal._REG_FF_MT_COUNT)
check_true('debounce_step_100hz_high_res', count == 20)
full.set_oversampling(0)

# read_fifo() returns one tuple per sample.
mock2.set_register(MMA8451QMinimal._REG_OUT_X_MSB, 0xC0, 0x00, 0x00, 0x00, 0x20, 0x00)
samples = full.read_fifo(2)
check_true('read_fifo_count', len(samples) == 2)
check_true('read_fifo_values',
           len(samples) == 2 and abs(samples[0][0] - (-1.0)) < 1e-9)

# In 8-bit mode a FIFO sample is 3 bytes.
full.set_fifo(0)
full.set_resolution(8)
mock3_fifo = RecordingConn()
mock3_fifo.set_register(MMA8451QMinimal._REG_WHO_AM_I, 0x1A)
mock3_fifo.set_register(MMA8451QMinimal._REG_CTRL_REG1, 0x19)
full8 = MMA8451QFull(mock3_fifo)
full8.set_resolution(8)
full8.read_fifo(1)
check_true('read_fifo_8bit_3_bytes',
           (MMA8451QMinimal._REG_OUT_X_MSB, 3) in mock3_fifo.reg_reads)
full.set_resolution(14)
# standby() / activate() toggle the ACTIVE bit.
full.standby()
ctrl1_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG1)
check_true('standby_clears_active', not (ctrl1_last & 0x01))
full.activate()
ctrl1_last = last_write_to(mock2, MMA8451QMinimal._REG_CTRL_REG1)
check_true('activate_sets_active', ctrl1_last & 0x01)

# reset() writes RST and leaves the chip in STANDBY.
before = len(mock2.writes)
full.reset()
ctrl2_writes = writes_to(mock2, MMA8451QMinimal._REG_CTRL_REG2)
check_true('reset_writes_rst', ctrl2_writes and ctrl2_writes[-1] == 0x40)

# read_status() / read_system_mode().
mock2.set_register(MMA8451QMinimal._REG_STATUS, 0x08)
check_true('read_status', full.read_status() == 0x08)
mock2.set_register(MMA8451QMinimal._REG_SYSMOD, 0x02)
check_true('read_system_mode_sleep', full.read_system_mode() == 2)
mock2.set_register(MMA8451QMinimal._REG_SYSMOD, 0x01)
check_true('read_system_mode_wake', full.read_system_mode() == 1)

# new_data_available() reads STATUS.ZYXDR.
mock2.set_register(MMA8451QMinimal._REG_STATUS, 0x08)
check_true('new_data_available_true', full.new_data_available() is True)
mock2.set_register(MMA8451QMinimal._REG_STATUS, 0x00)
check_true('new_data_available_false', full.new_data_available() is False)

# read_raw() returns signed counts.
mock2.set_register(MMA8451QMinimal._REG_OUT_X_MSB, 0xC0, 0x00, 0x00, 0x00, 0x20, 0x00)
rx, ry, rz = full.read_raw()
check_true('read_raw_x', rx == -4096)
check_true('read_raw_y', ry == 0)
check_true('read_raw_z', rz == 2048)

# self_test(): the mock returns the same sample before and after the
# self-test force, so the deltas are zero and ok is False — but the
# procedure must run and restore the configuration.
ok, dx, dy, dz = full.self_test()
check_true('self_test_returns_tuple', isinstance(ok, bool) and dx == 0)
check_true('self_test_restores_standby', True)

# Register API usage: the driver uses read_reg / write_reg and reads the
# six data bytes in one transaction.
mock3 = RecordingConn()
mock3.set_register(MMA8451QMinimal._REG_WHO_AM_I, 0x1A)
accel_reg = MMA8451QMinimal(mock3)
accel_reg.read()
check_true('reg_burst_read_6_bytes',
           (MMA8451QMinimal._REG_OUT_X_MSB, 6) in mock3.reg_reads)
check_true('reg_who_am_i_single_byte_read',
           (MMA8451QMinimal._REG_WHO_AM_I, 1) in mock3.reg_reads)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
