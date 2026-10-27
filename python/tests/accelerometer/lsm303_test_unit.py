"""Unit test for the LSM303 — runs without hardware using the I2C mock.

Uses two mocks (one per I²C slave: accelerometer 0x19, magnetometer 0x1E).
Verifies the init sequence, the two different I²C framings (accelerometer
auto-increment bit in the sub-address, magnetometer X/Z/Y pointer order),
sign-extension / sensitivity math, FIFO handling, interrupt-generator
threshold encoding, INT1/INT2 routing, and the magnetometer gain / rate /
mode registers.
"""

import sys
from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.accelerometer.lsm303 import LSM303Minimal, LSM303Full

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


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {}, expected {}'.format(label, got, expected))
        failed += 1


def last_write(mock, reg):
    """Return the data byte of the last 1-byte register write to ``reg``."""
    candidates = [w[1] for w in mock.writes if len(w) == 2 and w[0] == reg]
    return candidates[-1] if candidates else None


def raises(fn):
    try:
        fn()
    except ValueError:
        return True
    except Exception:
        return False
    return False


def new_mocks(identity=True):
    accel = I2CConnectionMock()
    mag = I2CConnectionMock()
    if identity:
        mag.set_register(LSM303Minimal._REG_IRA_M, 0x48, 0x34, 0x33)
    return accel, mag


# --- Minimal: init sequence ------------------------------------------------

accel, mag = new_mocks()
compass = LSM303Minimal(accel, mag)
check_true('construct_minimal', isinstance(compass, LSM303Minimal))

check_true('init_identity_read', any(w == bytes([0x0A]) or list(w) == [0x0A] for w in mag.writes))
check_eq('init_ctrl1_100hz_hr_axes', last_write(accel, LSM303Minimal._REG_CTRL1_A), 0x57)
check_eq('init_ctrl4_bdu_hr', last_write(accel, LSM303Minimal._REG_CTRL4_A), 0x88)
check_eq('init_ctrl2_clear', last_write(accel, LSM303Minimal._REG_CTRL2_A), 0x00)
check_eq('init_ctrl5_clear', last_write(accel, LSM303Minimal._REG_CTRL5_A), 0x00)
check_eq('init_cra_15hz', last_write(mag, LSM303Minimal._REG_CRA_M), 0x10)
check_eq('init_crb_130ut', last_write(mag, LSM303Minimal._REG_CRB_M), 0x20)
check_eq('init_mr_continuous', last_write(mag, LSM303Minimal._REG_MR_M), 0x00)

# Bad identity must raise.
accel2, mag2 = new_mocks(identity=False)
check_true('bad_identity_raises', raises(lambda: LSM303Minimal(accel2, mag2)))

# --- Minimal: conversions ---------------------------------------------------
# Accelerometer burst is read at sub-address 0x28 | 0x80 = 0xA8 (auto-increment).
# x = 0x1000 = +4096 → >>4 = 256 counts → 0.256 g at ±2 g HR;
# y = 0xFFF0 = -16 → >>4 = -1 → -0.001 g; z = 0x3E80 = 16000 → >>4 = 1000 → 1.0 g.
accel.set_register(LSM303Minimal._REG_OUT_X_L_A | 0x80,
                   0x00, 0x10, 0xF0, 0xFF, 0x80, 0x3E)
x, y, z = compass.read_acceleration()
check_true('read_acceleration_x', abs(x - 0.256) < 1e-9)
check_true('read_acceleration_y', abs(y - (-0.001)) < 1e-9)
check_true('read_acceleration_z', abs(z - 1.0) < 1e-9)

# Magnetometer on-wire order is X, Z, Y, high byte first, from 0x03.
# X = 1100 LSB/gauss → 100 µT; Z = 980 → 100 µT; Y = 855 → 100 µT.
mag.set_register(LSM303Minimal._REG_OUT_X_H_M,
                 0x04, 0x4C, 0x03, 0xD4, 0x04, 0x4C)
mx, my, mz = compass.read_magnetic()
check_true('read_magnetic_x', abs(mx - 100.0) < 1e-9)
check_true('read_magnetic_y', abs(my - 100.0) < 1e-9)
check_true('read_magnetic_z', abs(mz - 100.0) < 1e-9)

# --- Full: accelerometer configuration ---------------------------------------

accel3, mag3 = new_mocks()
full = LSM303Full(accel3, mag3)
check_true('construct_full', isinstance(full, LSM303Full))

# Magnetometer output block (X, Z, Y high-byte-first) and status, for the
# raw reads / heading / poll checks further down.
mag3.set_register(LSM303Full._REG_OUT_X_H_M,
                  0x04, 0x4C, 0x03, 0xD4, 0x04, 0x4C)
mag3.set_register(LSM303Full._REG_SR_M, 0x01)

full.set_range(16)
check_eq('set_range_16g', last_write(accel3, LSM303Full._REG_CTRL4_A), 0xB8)

full.set_data_rate(400)
check_eq('set_data_rate_400hz', last_write(accel3, LSM303Full._REG_CTRL1_A), 0x77)
check_true('set_data_rate_1620_rejected_hr', raises(lambda: full.set_data_rate(1620)))

full.set_mode(0)
check_eq('set_mode_lp_lpen', last_write(accel3, LSM303Full._REG_CTRL1_A), 0x7F)
check_eq('set_mode_lp_hr_cleared', last_write(accel3, LSM303Full._REG_CTRL4_A), 0xB0)
check_true('set_data_rate_1344_rejected_lp', raises(lambda: full.set_data_rate(1344)))
full.set_data_rate(5376)
check_eq('set_data_rate_5376_lp', last_write(accel3, LSM303Full._REG_CTRL1_A), 0x9F)
check_true('set_data_rate_bogus_rejected', raises(lambda: full.set_data_rate(123)))

full.set_mode(2)
check_eq('set_mode_hr', last_write(accel3, LSM303Full._REG_CTRL4_A), 0xB8)

full.power_down()
check_eq('power_down_odr_zero', last_write(accel3, LSM303Full._REG_CTRL1_A), 0x07)

full.set_axes(False, False, False)
check_eq('set_axes_none', last_write(accel3, LSM303Full._REG_CTRL1_A), 0x00)
full.set_axes()
check_eq('set_axes_all', last_write(accel3, LSM303Full._REG_CTRL1_A), 0x07)

full.set_data_rate(100)
check_eq('restore_data_rate_100hz', last_write(accel3, LSM303Full._REG_CTRL1_A), 0x57)

# Sensitivity follows mode and range: ±16 g HR = 12 mg/LSB.
accel3.set_register(LSM303Full._REG_OUT_X_L_A | 0x80, 0x00, 0x10, 0xF0, 0xFF, 0x80, 0x3E)
x, y, z = full.read_acceleration()
check_true('sensitivity_16g_hr', abs(x - 3.072) < 1e-9 and abs(z - 12.0) < 1e-9)
rx, ry, rz = full.read_acceleration_raw()
check_eq('read_acceleration_raw_x', rx, 256)
check_eq('read_acceleration_raw_y', ry, -1)
check_eq('read_acceleration_raw_z', rz, 1000)

check_true('new_data_available', full.new_data_available() is False)
accel3.set_register(LSM303Full._REG_STATUS_A, 0x08)
check_true('new_data_available_zyxda', full.new_data_available() is True)
check_eq('read_status', full.read_status(), 0x08)

# --- Full: high-pass filter ---------------------------------------------------

full.set_high_pass(mode=3, cutoff=2, filter_output=True, on_ia1=True)
check_eq('set_high_pass', last_write(accel3, LSM303Full._REG_CTRL2_A), 0xE9)
accel3.set_register(LSM303Full._REG_REFERENCE_A, 0x2A)
check_eq('reset_high_pass_returns_reference', full.reset_high_pass(), 0x2A)
full.set_reference(0x55)
check_eq('set_reference', last_write(accel3, LSM303Full._REG_REFERENCE_A), 0x55)

# --- Full: FIFO ---------------------------------------------------------------

full.set_fifo(2, 8)
check_eq('set_fifo_stream_wm8', last_write(accel3, LSM303Full._REG_FIFO_CTRL_A), 0x88)
check_eq('set_fifo_enables_fifo_en', last_write(accel3, LSM303Full._REG_CTRL5_A), 0x40)
accel3.set_register(LSM303Full._REG_FIFO_SRC_A, 0x86)
check_eq('fifo_level_fss', full.fifo_level(), 6)
wm, ov, empty, level = full.read_fifo_status()
check_true('fifo_status_flags', wm is True and ov is False and empty is False and level == 6)
samples = full.read_fifo()
check_eq('read_fifo_count', len(samples), 6)
check_true('read_fifo_values', abs(samples[0][0] - 3.072) < 1e-9 and abs(samples[0][2] - 12.0) < 1e-9)
full.set_fifo(0)
check_eq('fifo_bypass_clears_en', last_write(accel3, LSM303Full._REG_CTRL5_A), 0x00)

# --- Full: interrupt generators (threshold encoding at ±16 g, 100 Hz) ---------

full.set_free_fall(1, 0.5, 100)
check_eq('free_fall_cfg_0x95', last_write(accel3, LSM303Full._REG_INT1_CFG_A), 0x95)
check_eq('free_fall_ths', last_write(accel3, LSM303Full._REG_INT1_THS_A), 4)
check_eq('free_fall_dur', last_write(accel3, LSM303Full._REG_INT1_DUR_A), 10)

full.set_motion_interrupt(2, 0x15, 0.25, 50, and_mode=True)
check_eq('motion_cfg_and_or', last_write(accel3, LSM303Full._REG_INT2_CFG_A), 0x95)
check_eq('motion_ths', last_write(accel3, LSM303Full._REG_INT2_THS_A), 2)
check_eq('motion_dur', last_write(accel3, LSM303Full._REG_INT2_DUR_A), 5)

full.set_orientation(2, position=True, four_d=True)
check_eq('orientation_6d_position_cfg', last_write(accel3, LSM303Full._REG_INT2_CFG_A), 0xFF)
check_eq('orientation_4d_d4d_int2', last_write(accel3, LSM303Full._REG_CTRL5_A), 0x01)

full.set_latch(1, True)
check_eq('latch_int1', last_write(accel3, LSM303Full._REG_CTRL5_A), 0x09)
full.set_latch(1, False)
check_eq('unlatch_int1', last_write(accel3, LSM303Full._REG_CTRL5_A), 0x01)

accel3.set_register(LSM303Full._REG_INT1_SRC_A, 0x7F)
check_eq('read_interrupt_source', full.read_interrupt_source(1), 0x7F)

# Click: axis masks X=1/Y=2/Z=4 spread to CLICK_CFG bits (XD=0, XS=1, YD=2, ...).
full.set_click(single=1, double=2, threshold_g=1.0, time_limit_ms=10, latency_ms=20, window_ms=50)
check_eq('click_cfg_masks', last_write(accel3, LSM303Full._REG_CLICK_CFG_A), 0x06)
check_eq('click_ths', last_write(accel3, LSM303Full._REG_CLICK_THS_A), 8)
check_eq('click_time_limit', last_write(accel3, LSM303Full._REG_TIME_LIMIT_A), 1)
check_eq('click_latency', last_write(accel3, LSM303Full._REG_TIME_LAT_A), 2)
check_eq('click_window', last_write(accel3, LSM303Full._REG_TIME_WIN_A), 5)
accel3.set_register(LSM303Full._REG_CLICK_SRC_A, 0x40)
check_eq('read_click_source', full.read_click_source(), 0x40)

full.set_interrupt_polarity(True)
check_eq('interrupt_polarity_active_low', last_write(accel3, LSM303Full._REG_CTRL6_A), 0x02)
full.set_interrupt_polarity(False)

# --- Full: INT1 / INT2 routing -------------------------------------------------

full.enable_interrupt(LSM303Full.SOURCE_IA1, 1)
check_eq('route_ia1_int1', last_write(accel3, LSM303Full._REG_CTRL3_A), 0x40)
full.enable_interrupt(LSM303Full.SOURCE_CLICK, 2)
check_eq('route_click_int2', last_write(accel3, LSM303Full._REG_CTRL6_A), 0x80)
check_true('route_mag_drdy_rejected',
           raises(lambda: full.enable_interrupt(LSM303Full.SOURCE_MAG_DATA_READY)))
check_true('route_boot_int1_rejected',
           raises(lambda: full.enable_interrupt(LSM303Full.SOURCE_BOOT, 1)))
full.enable_interrupt(LSM303Full.SOURCE_BOOT, 2)
check_eq('route_boot_int2', last_write(accel3, LSM303Full._REG_CTRL6_A), 0x90)

# poll_interrupt reads only the enabled sources: IA1 (0x7F → IA set),
# CLICK (0x40 → IA set), and the magnetometer DRDY (SR = 0x01).
status = full.poll_interrupt()
check_eq('poll_interrupt_combined', status,
         LSM303Full.SOURCE_IA1 | LSM303Full.SOURCE_CLICK | LSM303Full.SOURCE_MAG_DATA_READY)

full.disable_interrupt(LSM303Full.SOURCE_IA1, 1)
check_eq('unroute_ia1_int1', last_write(accel3, LSM303Full._REG_CTRL3_A), 0x00)
full.disable_interrupt(LSM303Full.SOURCE_CLICK, 2)
check_eq('unroute_click_int2', last_write(accel3, LSM303Full._REG_CTRL6_A), 0x10)

# --- Full: magnetometer configuration ------------------------------------------

full.set_mag_range(810)
check_eq('set_mag_range_810ut', last_write(mag3, LSM303Full._REG_CRB_M), 0xE0)
check_true('set_mag_range_bogus_rejected', raises(lambda: full.set_mag_range(200)))
mrx, mry, mrz = full.read_magnetic_raw()
check_eq('read_magnetic_raw_x', mrx, 1100)
check_eq('read_magnetic_raw_y', mry, 1100)
check_eq('read_magnetic_raw_z', mrz, 980)

full.set_mag_data_rate(220)
check_eq('set_mag_data_rate_220hz', last_write(mag3, LSM303Full._REG_CRA_M), 0x1C)
check_true('set_mag_data_rate_bogus_rejected', raises(lambda: full.set_mag_data_rate(50)))
full.set_mag_data_rate(15)

full.set_mag_mode(1)
check_eq('set_mag_mode_single', last_write(mag3, LSM303Full._REG_MR_M), 0x01)
full.set_mag_mode(0)

mag3.set_register(LSM303Full._REG_SR_M, 0x01)
check_true('mag_data_ready', full.mag_data_ready() is True)
drdy, lock = full.read_mag_status()
check_true('read_mag_status', drdy is True and lock is False)
check_eq('read_mag_identity', full.read_mag_identity(), b'\x48\x34\x33')

full.enable_temperature(True)
check_eq('enable_temperature', last_write(mag3, LSM303Full._REG_CRA_M), 0x90)
# TEMP_OUT = 0x0C00 → 3072 >> 4 = 192 → 192 / 8 = 24.0 °C delta.
mag3.set_register(LSM303Full._REG_TEMP_H_M, 0x0C, 0x00)
check_true('read_temperature_delta', abs(full.read_temperature_delta() - 24.0) < 1e-9)

# --- Full: heading and reboot ---------------------------------------------------

# Flat compass: X and Y both at +100 µT → atan2(100, 100) = 45°.
check_true('heading_flat', abs(full.heading(tilt_compensate=False) - 45.0) < 1e-6)
hdg = full.heading()
check_true('heading_tilt_compensated_range', 0.0 <= hdg < 360.0)

ctrl5_before = last_write(accel3, LSM303Full._REG_CTRL5_A)
full.reboot()
check_eq('reboot_sets_boot_bit', last_write(accel3, LSM303Full._REG_CTRL5_A), ctrl5_before | 0x80)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
