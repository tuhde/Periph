"""Unit test for the LSM303 — runs without hardware using the I2C mock.

Verifies the two-connection register sequencing, scale conversion, the
magnetometer X/Z/Y reordering, gain handling and threshold encoding by
observing what the driver writes to and reads from the mocks.
"""

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


def make_mocks():
    """Create a preloaded accelerometer and magnetometer mock."""
    accel = I2CConnectionMock()
    mag = I2CConnectionMock()
    mag.set_register(0x0A, 0x48, 0x34, 0x33)
    # High-resolution (±2 g, shift 4): raw 0x0010/0x0020/0x0030 -> 1/2/3 mg.
    accel.set_register(0x28 | 0x80, 0x10, 0x00, 0x20, 0x00, 0x30, 0x00)
    # Magnetometer outputs (X, Z, Y; high byte first).
    mag.set_register(0x03, 0x04, 0x4C,   # X = 1100 -> 100 uT at 1100 LSB/G
                     0x03, 0xD4,        # Z = 980  -> 100 uT at 980 LSB/G
                     0xFB, 0xB4)        # Y = -1100 -> -100 uT
    return accel, mag


accel_mock, mag_mock = make_mocks()
chip = LSM303Minimal(accel_mock, mag_mock)
check_true('construct_minimal', True)

# Initialisation writes the accelerometer defaults.
ctrl4 = [w for w in accel_mock.writes if len(w) == 2 and w[0] == 0x23]
check_true('init_ctrl_reg4_default', ctrl4 and ctrl4[-1][1] == 0x88)
ctrl1 = [w for w in accel_mock.writes if len(w) == 2 and w[0] == 0x20]
check_true('init_ctrl_reg1_default', ctrl1 and ctrl1[-1][1] == 0x57)
ctrl5 = [w for w in accel_mock.writes if len(w) == 2 and w[0] == 0x24]
check_true('init_ctrl_reg5_cleared', ctrl5 and ctrl5[-1][1] == 0x00)

# Initialisation writes the magnetometer defaults (continuous conversion).
mr = [w for w in mag_mock.writes if len(w) == 2 and w[0] == 0x02]
check_true('init_mag_continuous', mr and mr[-1][1] == 0x00)
crb = [w for w in mag_mock.writes if len(w) == 2 and w[0] == 0x01]
check_true('init_mag_gain_130ut', crb and crb[-1][1] == 0x20)

ax, ay, az = chip.read_acceleration()
check_true('acceleration_x', abs(ax - 0.001) < 1e-9)
check_true('acceleration_y', abs(ay - 0.002) < 1e-9)
check_true('acceleration_z', abs(az - 0.003) < 1e-9)

# Accelerometer burst read uses auto-increment (0x28 | 0x80).
burst = [w for w in accel_mock.writes if bytes([0xA8]) == w]
check_true('accel_burst_auto_increment', len(burst) >= 1)

mx, my, mz = chip.read_magnetic()
check_true('magnetic_x', abs(mx - 100.0) < 1e-6)
check_true('magnetic_y', abs(my + 100.0) < 1e-6)
check_true('magnetic_z', abs(mz - 100.0) < 1e-6)
check_true('magnetic_order_xyz', abs(mx - 100.0) < 1e-6 and abs(mz - 100.0) < 1e-6)

# Range change: ±4 g doubles the sensitivity (1 count -> 2 mg).
chip_full = LSM303Full(*make_mocks())
chip_full.set_range(4)
ax, ay, az = chip_full.read_acceleration()
check_true('set_range_4g_sensitivity', abs(ax - 0.002) < 1e-9)
ctrl4 = [w for w in chip_full._accel.writes if len(w) == 2 and w[0] == 0x23]
check_true('set_range_4g_fs_bits', ctrl4 and (ctrl4[-1][1] & 0x30) == 0x10)

# Data-rate validation.
chip_full.set_data_rate(400)
ctrl1 = [w for w in chip_full._accel.writes if len(w) == 2 and w[0] == 0x20]
check_true('set_data_rate_400hz', ctrl1 and (ctrl1[-1][1] >> 4) == 0x07)
rejected = False
try:
    chip_full.set_data_rate(5376)
except ValueError:
    rejected = True
check_true('set_data_rate_5376_rejected_in_hr', rejected)

# Mode change: normal mode sets LPen=0 and HR=0.
chip_full.set_mode(1)
ctrl4 = [w for w in chip_full._accel.writes if len(w) == 2 and w[0] == 0x23]
check_true('set_mode_normal_hr_clear', ctrl4 and (ctrl4[-1][1] & 0x08) == 0)

# Threshold encoding: 0.5 g at ±2 g -> round(0.5 / (2/128)) = 32.
chip_full.set_range(2)
chip_full.set_motion_interrupt(1, 0x3F, 0.5, 0, True)
ths = [w for w in chip_full._accel.writes if len(w) == 2 and w[0] == 0x32]
cfg = [w for w in chip_full._accel.writes if len(w) == 2 and w[0] == 0x30]
check_true('motion_threshold_code', ths and ths[-1][1] == 32)
check_true('motion_and_mode', cfg and (cfg[-1][1] & 0x80) == 0x80)

# Free-fall config is 0x95.
chip_full.set_free_fall(1, 0.35, 30)
cfg = [w for w in chip_full._accel.writes if len(w) == 2 and w[0] == 0x30]
check_true('free_fall_cfg', cfg and cfg[-1][1] == 0x95)

# Click: double-click on Z sets CLICK_CFG bit 5.
chip_full.set_click(0, 0x04, 0.5, 10, 20, 50)
click = [w for w in chip_full._accel.writes if len(w) == 2 and w[0] == 0x38]
check_true('click_double_z', click and (click[-1][1] & 0x20) == 0x20)

# Interrupt routing: data-ready to INT1 sets CTRL_REG3 bit 4.
from periph.chips.accelerometer.lsm303 import LSM303Source
chip_full.enable_interrupt(LSM303Source.SOURCE_DATA_READY, 1)
ctrl3 = [w for w in chip_full._accel.writes if len(w) == 2 and w[0] == 0x22]
check_true('route_data_ready_int1', ctrl3 and (ctrl3[-1][1] & 0x10) == 0x10)

# Click routed to INT2 sets CTRL_REG6 bit 7.
chip_full.enable_interrupt(LSM303Source.SOURCE_CLICK, 2)
ctrl6 = [w for w in chip_full._accel.writes if len(w) == 2 and w[0] == 0x25]
check_true('route_click_int2', ctrl6 and (ctrl6[-1][1] & 0x80) == 0x80)

# Magnetometer DRDY is not routable.
rejected = False
try:
    chip_full.enable_interrupt(LSM303Source.SOURCE_MAG_DATA_READY, 1)
except ValueError:
    rejected = True
check_true('mag_drdy_not_routable', rejected)

# Magnetometer range: 470 uT -> GN = 101 (0xA0).
chip_full.set_mag_range(470)
crb = [w for w in chip_full._mag.writes if len(w) == 2 and w[0] == 0x01]
check_true('set_mag_range_470ut', crb and crb[-1][1] == 0xA0)

# Gain change: at 470 uT the X/Y gain is 400 LSB/G, so 400 counts = 100 uT.
chip_full._mag.set_register(0x03, 0x01, 0x90,   # X = 400
                            0x01, 0x8F,         # Z = 399 (~99.75 uT at 355 LSB/G... only X checked)
                            0x01, 0x8C)         # Y = 396
mx, my, mz = chip_full.read_magnetic()
check_true('mag_gain_470ut_x', abs(mx - 100.0) < 1e-6)

# Data rate: 30 Hz -> DO = 101 (0x14), TEMP_EN preserved.
chip_full.enable_temperature(True)
chip_full.set_mag_data_rate(30)
cra = [w for w in chip_full._mag.writes if len(w) == 2 and w[0] == 0x00]
check_true('set_mag_data_rate_30hz', cra and (cra[-1][1] & 0x1C) == 0x14)
check_true('set_mag_data_rate_keeps_temp', cra and (cra[-1][1] & 0x80) == 0x80)

# Identity.
check_true('mag_identity', chip_full.read_mag_identity() == b'\x48\x34\x33')

# Temperature: raw 0x0080 >> 4 = 8 -> +1.0 degC.
chip_full._mag.set_register(0x31, 0x00, 0x80)
check_true('temperature_delta', abs(chip_full.read_temperature_delta() - 1.0) < 1e-9)

# Heading: flat compass with mx=1, my=0 -> 0 degrees.
chip_heading = LSM303Full(*make_mocks())
chip_heading._mag.set_register(0x03, 0x00, 0x64,   # X = 100 -> 9.09 uT
                               0x03, 0xD4,         # Z
                               0x00, 0x00)         # Y = 0
h = chip_heading.heading(tilt_compensate=False)
check_true('heading_flat', abs(h) < 1e-6 or abs(h - 360.0) < 1e-6)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
if failed:
    raise SystemExit(1)
