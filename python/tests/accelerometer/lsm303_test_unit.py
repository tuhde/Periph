"""Unit test for the LSM303 — runs without hardware using the I2C mock.

Two mocks are used, one per I²C slave: the accelerometer at 0x19 and the
magnetometer at 0x1E. The test verifies the initialization sequence, the
two different I²C framings (the accelerometer's auto-increment bit in the
sub-address, the magnetometer's X/Z/Y pointer order), the sign-extension
and sensitivity maths, the FIFO handling, the interrupt-generator threshold
encoding, the INT1/INT2 routing and the magnetometer gain / rate / mode
registers.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.accelerometer.lsm303 import (
    LSM303Minimal, LSM303Full, LSM303Source,
    I2C_ADDRESS_ACCEL, I2C_ADDRESS_MAG)

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


def check_close(label, got, expected, tolerance=1e-6):
    global passed, failed
    if abs(got - expected) <= tolerance:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {}, expected {}'.format(label, got, expected))
        failed += 1


def raises(fn, exception=ValueError):
    try:
        fn()
    except exception:
        return True
    except Exception:
        return False
    return False


def writes_to(mock, reg):
    """Return every 1-byte register write to ``reg``."""
    return [w for w in mock.writes if len(w) == 2 and w[0] == reg]


def last_write(mock, reg):
    """Return the data byte of the last 1-byte register write to ``reg``."""
    candidates = writes_to(mock, reg)
    return candidates[-1][1] if candidates else None


def reads_from(mock, reg):
    """Return every (register address) read from ``reg``."""
    return [w[0] for w in mock.writes if len(w) == 1 and w[0] == reg]


# The accelerometer sets bit 7 of the sub-address for multi-byte transfers,
# so the driver reads the output registers from 0x28 | 0x80. The mock
# resolves the address literally.
ACCEL_OUT = 0x28 | 0x80
MAG_OUT = 0x03


def new_mocks(identity=True):
    accel = I2CConnectionMock()
    mag = I2CConnectionMock()
    if identity:
        mag.set_register(0x0A, 0x48, 0x34, 0x33)
    return accel, mag


def set_accel_output(accel, x, y, z):
    """Preload the accelerometer output registers with 12-bit counts.

    The high-resolution mode writes the 12-bit value left-justified into the
    16-bit register pair.
    """
    set_accel_raw(accel, x << 4, y << 4, z << 4)


def set_accel_raw(accel, x, y, z):
    """Preload the accelerometer output registers with raw 16-bit values."""
    accel.set_register(ACCEL_OUT,
                       x & 0xFF, (x >> 8) & 0xFF,
                       y & 0xFF, (y >> 8) & 0xFF,
                       z & 0xFF, (z >> 8) & 0xFF)


def set_mag_output(mag, x, y, z):
    """Preload the magnetometer output registers (X, Z, Y on the wire)."""
    mag.set_register(MAG_OUT,
                     (x >> 8) & 0xFF, x & 0xFF,
                     (z >> 8) & 0xFF, z & 0xFF,
                     (y >> 8) & 0xFF, y & 0xFF)


# --- Addresses -------------------------------------------------------------
check_eq('accel_address', I2C_ADDRESS_ACCEL, 0x19)
check_eq('mag_address', I2C_ADDRESS_MAG, 0x1E)

# --- Minimal: initialization sequence -------------------------------------
accel, mag = new_mocks()
compass = LSM303Minimal(accel, mag)
check_true('construct_minimal', isinstance(compass, LSM303Minimal))

check_eq('init_ctrl2_cleared', last_write(accel, 0x21), 0x00)
check_eq('init_ctrl3_cleared', last_write(accel, 0x22), 0x00)
check_eq('init_ctrl5_cleared', last_write(accel, 0x24), 0x00)
check_eq('init_ctrl6_cleared', last_write(accel, 0x25), 0x00)
check_eq('init_fifo_ctrl_cleared', last_write(accel, 0x2E), 0x00)
check_eq('init_ctrl4_bdu_hr', last_write(accel, 0x23), 0x88)
check_eq('init_ctrl1_100hz_axes', last_write(accel, 0x20), 0x57)
check_eq('init_cra_15hz', last_write(mag, 0x00), 0x10)
check_eq('init_crb_130ut', last_write(mag, 0x01), 0x20)
check_eq('init_mr_continuous', last_write(mag, 0x02), 0x00)
check_true('init_reads_identity', 0x0A in reads_from(mag, 0x0A))

# A wrong magnetometer identity must be rejected.
bad_accel, bad_mag = new_mocks(identity=False)
check_true('bad_identity_raises', raises(lambda: LSM303Minimal(bad_accel, bad_mag)))

# --- Minimal: acceleration -------------------------------------------------
accel, mag = new_mocks()
compass = LSM303Minimal(accel, mag)
set_accel_output(accel, 1, -1, 512)
ax, ay, az = compass.read_acceleration()
check_close('accel_x', ax, 0.001)
check_close('accel_y', ay, -0.001)
check_close('accel_z', az, 0.512)
check_true('accel_uses_auto_increment', ACCEL_OUT in reads_from(accel, ACCEL_OUT))

# --- Minimal: magnetic field ----------------------------------------------
accel, mag = new_mocks()
compass = LSM303Minimal(accel, mag)
set_mag_output(mag, 1100, 0, -980)
mx, my, mz = compass.read_magnetic()
check_close('mag_x', mx, 100.0)
check_close('mag_y', my, 0.0)
check_close('mag_z', mz, -100.0)
check_true('mag_reads_from_0x03', MAG_OUT in reads_from(mag, MAG_OUT))
check_true('mag_no_auto_increment_bit', 0x83 not in reads_from(mag, 0x83))

# --- Full: range, rate, mode, axes ----------------------------------------
accel, mag = new_mocks()
full = LSM303Full(accel, mag)

full.set_range(8)
check_eq('set_range_fs_bits', accel.registers[0x23] & 0x30, 0x20)
set_accel_output(accel, 128, 0, 0)
check_close('range_changes_sensitivity', full.read_acceleration()[0], 0.512)
check_true('set_range_rejects_invalid', raises(lambda: full.set_range(5)))

full.set_range(2)
full.set_data_rate(400)
check_eq('set_data_rate_400hz', accel.registers[0x20] & 0xF0, 0x70)
full.set_data_rate(0)
check_eq('set_data_rate_power_down', accel.registers[0x20] & 0xF0, 0x00)
full.set_data_rate(100)
check_true('set_data_rate_rejects_invalid', raises(lambda: full.set_data_rate(123)))

full.set_mode(LSM303Full.MODE_LOW_POWER)
check_true('mode_low_power_lpen', accel.registers[0x20] & 0x08)
check_true('mode_low_power_no_hr', not accel.registers[0x23] & 0x08)
set_accel_raw(accel, 0x0100, 0, 0)
check_close('low_power_sensitivity', full.read_acceleration()[0], 0.016)
check_true('low_power_allows_5376hz', not raises(lambda: full.set_data_rate(5376)))
check_true('low_power_rejects_1344hz', raises(lambda: full.set_data_rate(1344)))
full.set_data_rate(100)

full.set_mode(LSM303Full.MODE_NORMAL)
check_true('mode_normal_no_lpen', not accel.registers[0x20] & 0x08)
check_true('mode_normal_no_hr', not accel.registers[0x23] & 0x08)
set_accel_raw(accel, 0x0040, 0, 0)
check_close('normal_mode_sensitivity', full.read_acceleration()[0], 0.004)
check_true('normal_rejects_1620hz', raises(lambda: full.set_data_rate(1620)))
check_true('normal_rejects_5376hz', raises(lambda: full.set_data_rate(5376)))
full.set_data_rate(100)

full.set_mode(LSM303Full.MODE_HIGH_RESOLUTION)
check_true('mode_high_res_hr', accel.registers[0x23] & 0x08)
check_true('set_mode_rejects_invalid', raises(lambda: full.set_mode(7)))

full.set_axes(x=True, y=False, z=True)
check_eq('set_axes_mask', accel.registers[0x20] & 0x07, 0x05)

full.power_down()
check_eq('power_down_clears_odr', accel.registers[0x20] & 0xF0, 0x00)
full.set_data_rate(100)

# --- Full: status ----------------------------------------------------------
accel.set_register(0x27, 0x08)
check_true('new_data_available', full.new_data_available())
check_eq('read_status', full.read_status(), 0x08)

# --- Full: high-pass filter ------------------------------------------------
full.set_high_pass(mode=1, cutoff=2, filter_output=True,
                   on_ia1=True, on_ia2=False, on_click=True)
check_eq('high_pass_ctrl2', accel.registers[0x21], 0x40 | 0x20 | 0x08 | 0x04 | 0x01)
check_true('high_pass_rejects_invalid_mode', raises(lambda: full.set_high_pass(mode=4)))
check_true('high_pass_rejects_invalid_cutoff', raises(lambda: full.set_high_pass(cutoff=9)))

accel.set_register(0x26, 0x5A)
check_eq('reset_high_pass_returns_reference', full.reset_high_pass(), 0x5A)
full.set_reference(0x33)
check_eq('set_reference', accel.registers[0x26], 0x33)

# --- Full: FIFO ------------------------------------------------------------
full.set_fifo(LSM303Full.FIFO_STREAM, watermark=16)
check_eq('fifo_ctrl_mode_watermark', accel.registers[0x2E], 0x80 | 16)
check_true('fifo_enabled', accel.registers[0x24] & 0x40)
accel.set_register(0x2F, 0x80 | 0x05)
check_eq('fifo_level', full.fifo_level(), 5)
check_eq('fifo_status', full.read_fifo_status(), (True, False, False, 5))
samples = full.read_fifo()
check_eq('fifo_sample_count', len(samples), 5)
check_true('fifo_rejects_invalid_mode', raises(lambda: full.set_fifo(4)))
check_true('fifo_rejects_invalid_watermark', raises(lambda: full.set_fifo(1, watermark=32)))
full.set_fifo(LSM303Full.FIFO_BYPASS)
check_true('fifo_disabled_in_bypass', not accel.registers[0x24] & 0x40)

# --- Full: inertial interrupt generators ------------------------------------
full.set_motion_interrupt(1, 0x02, threshold_g=0.5, duration_ms=10, and_mode=False)
check_eq('motion_int_cfg', accel.registers[0x30], 0x02)
check_eq('motion_int_threshold', accel.registers[0x32], 32)
check_eq('motion_int_duration', accel.registers[0x33], 1)
full.set_motion_interrupt(2, 0x3F, threshold_g=2.0, duration_ms=10, and_mode=True)
check_eq('motion_int2_cfg', accel.registers[0x34], 0x80 | 0x3F)
check_eq('motion_int2_threshold', accel.registers[0x36], 127)
check_true('motion_int_rejects_invalid_generator', raises(lambda: full.set_motion_interrupt(3, 1, 0.5)))
check_true('motion_int_rejects_invalid_events', raises(lambda: full.set_motion_interrupt(1, 0x40, 0.5)))

full.set_free_fall(generator=1, threshold_g=0.35, duration_ms=30)
check_eq('free_fall_cfg', accel.registers[0x30], 0x95)
check_eq('free_fall_threshold', accel.registers[0x32], 22)
check_eq('free_fall_duration', accel.registers[0x33], 3)

full.set_orientation(generator=1, position=False, four_d=False)
check_eq('orientation_6d_cfg', accel.registers[0x30], 0x40 | 0x3F)
full.set_orientation(generator=1, position=True, four_d=False)
check_eq('orientation_position_cfg', accel.registers[0x30], 0xC0 | 0x3F)
full.set_orientation(generator=2, position=False, four_d=True)
check_eq('orientation_4d_cfg', accel.registers[0x34], 0x40 | 0x0F)
check_true('orientation_4d_sets_d4d', accel.registers[0x24] & 0x01)
full.set_orientation(generator=2, position=False, four_d=False)
check_true('orientation_4d_cleared', not accel.registers[0x24] & 0x01)

full.set_latch(1, True)
check_true('latch_int1', accel.registers[0x24] & 0x08)
full.set_latch(2, True)
check_true('latch_int2', accel.registers[0x24] & 0x02)
full.set_latch(1, False)
check_true('unlatch_int1', not accel.registers[0x24] & 0x08)
check_true('set_latch_rejects_invalid_generator', raises(lambda: full.set_latch(3, True)))

accel.set_register(0x31, 0x40)
check_eq('read_interrupt_source_1', full.read_interrupt_source(1), 0x40)
accel.set_register(0x35, 0x41)
check_eq('read_interrupt_source_2', full.read_interrupt_source(2), 0x41)
check_true('read_interrupt_source_rejects_invalid', raises(lambda: full.read_interrupt_source(0)))

# --- Full: click detection --------------------------------------------------
full.set_click(single=0x01, double=0x04, threshold_g=0.8,
                time_limit_ms=20, latency_ms=40, window_ms=200)
check_eq('click_cfg', accel.registers[0x38], 0x01 | 0x20)
check_eq('click_threshold', accel.registers[0x3A], 51)
check_eq('click_time_limit', accel.registers[0x3B], 2)
check_eq('click_time_latency', accel.registers[0x3C], 4)
check_eq('click_time_window', accel.registers[0x3D], 20)
check_true('click_rejects_invalid_mask', raises(lambda: full.set_click(single=0x08)))

accel.set_register(0x39, 0x40)
check_eq('read_click_source', full.read_click_source(), 0x40)

# --- Full: interrupt polarity -----------------------------------------------
full.set_interrupt_polarity(True)
check_true('polarity_active_low', accel.registers[0x25] & 0x02)
full.set_interrupt_polarity(False)
check_true('polarity_active_high', not accel.registers[0x25] & 0x02)

# --- Full: interrupt routing ------------------------------------------------
full.enable_interrupt(LSM303Source.SOURCE_CLICK, 1)
check_true('route_click_to_int1', accel.registers[0x22] & 0x80)
full.enable_interrupt(LSM303Source.SOURCE_CLICK, 2)
check_true('route_click_to_int2', accel.registers[0x25] & 0x80)
full.disable_interrupt(LSM303Source.SOURCE_CLICK, 1)
check_true('unroute_click_from_int1', not accel.registers[0x22] & 0x80)
full.enable_interrupt(LSM303Source.SOURCE_IA1, 2)
check_true('route_ia1_to_int2', accel.registers[0x25] & 0x40)
full.enable_interrupt(LSM303Source.SOURCE_BOOT, 2)
check_true('route_boot_to_int2', accel.registers[0x25] & 0x10)
full.enable_interrupt(LSM303Source.SOURCE_DATA_READY, 1)
check_true('route_data_ready_to_int1', accel.registers[0x22] & 0x10)
check_true('route_rejects_mag_source',
           raises(lambda: full.enable_interrupt(LSM303Source.SOURCE_MAG_DATA_READY)))
check_true('route_rejects_invalid_pin',
           raises(lambda: full.enable_interrupt(LSM303Source.SOURCE_CLICK, 3)))

# --- Full: poll_interrupt ----------------------------------------------------
accel.set_register(0x27, 0x08)
accel.set_register(0x2F, 0x80 | 0x40)
accel.set_register(0x31, 0x40)
accel.set_register(0x35, 0x40)
accel.set_register(0x39, 0x40)
mag.set_register(0x09, 0x01)
status = full.poll_interrupt()
check_true('poll_interrupt_data_ready', status & LSM303Source.SOURCE_DATA_READY)
check_true('poll_interrupt_watermark', status & LSM303Source.SOURCE_WATERMARK)
check_true('poll_interrupt_overrun', status & LSM303Source.SOURCE_OVERRUN)
check_true('poll_interrupt_ia1', status & LSM303Source.SOURCE_IA1)
check_true('poll_interrupt_mag_drdy', status & LSM303Source.SOURCE_MAG_DATA_READY)

# --- Full: magnetometer ------------------------------------------------------
full.set_mag_range(250)
check_eq('mag_range_gn_bits', mag.registers[0x01] & 0xE0, 0x60)
set_mag_output(mag, 670, 0, 0)
check_close('mag_range_changes_gain', full.read_magnetic()[0], 100.0)
check_true('mag_range_rejects_invalid', raises(lambda: full.set_mag_range(123)))

full.set_mag_data_rate(30)
check_eq('mag_data_rate_do', mag.registers[0x00] & 0x1C, 5 << 2)
check_true('mag_data_rate_rejects_invalid', raises(lambda: full.set_mag_data_rate(12)))

full.set_mag_mode(LSM303Full.MAG_MODE_SLEEP)
check_eq('mag_mode_sleep', mag.registers[0x02] & 0x03, 2)
full.set_mag_mode(LSM303Full.MAG_MODE_SINGLE)
check_eq('mag_mode_single', mag.registers[0x02] & 0x03, 1)
full.set_mag_mode(LSM303Full.MAG_MODE_CONTINUOUS)
check_eq('mag_mode_continuous', mag.registers[0x02] & 0x03, 0)
check_true('mag_mode_rejects_invalid', raises(lambda: full.set_mag_mode(5)))

check_eq('read_mag_identity', full.read_mag_identity(), b'\x48\x34\x33')
mag.set_register(0x09, 0x03)
check_eq('read_mag_status', full.read_mag_status(), (True, True))
check_true('mag_data_ready', full.mag_data_ready())

full.enable_temperature(True)
check_true('temperature_enabled', mag.registers[0x00] & 0x80)
full.set_mag_data_rate(15)
check_true('temperature_kept_on_rate_change', mag.registers[0x00] & 0x80)
mag.set_register(0x31, 0x02, 0x80)
check_close('temperature_delta', full.read_temperature_delta(), 5.0)
full.enable_temperature(False)
check_true('temperature_disabled', not mag.registers[0x00] & 0x80)

# --- Full: heading -----------------------------------------------------------
accel, mag = new_mocks()
full = LSM303Full(accel, mag)
set_mag_output(mag, 1100, 0, 0)
set_accel_output(accel, 0, 0, 0)
check_close('heading_north', full.heading(), 0.0, 1e-4)
set_mag_output(mag, 0, 1100, 0)
check_close('heading_east', full.heading(), 90.0, 1e-4)
set_mag_output(mag, -1100, 0, 0)
check_close('heading_south', full.heading(), 180.0, 1e-4)
set_mag_output(mag, 0, -1100, 0)
check_close('heading_west', full.heading(), 270.0, 1e-4)

# --- Full: reboot ------------------------------------------------------------
accel, mag = new_mocks()
full = LSM303Full(accel, mag)
full.reboot()
check_true('reboot_sets_boot_bit', any(w[1] & 0x80 for w in writes_to(accel, 0x24)))
check_eq('reboot_restores_defaults', last_write(accel, 0x20), 0x57)

print('===DONE: %d passed, %d failed===' % (passed, failed))
