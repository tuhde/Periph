import math
import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.imu.mpu9255 import MPU9255Full, MPU9255Minimal

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


def s16(value):
    """Encode a signed 16-bit value as its two big-endian bytes."""
    return ((value & 0xFFFF) >> 8) & 0xFF, value & 0xFF


def s16le(value):
    """Encode a signed 16-bit value as its two little-endian bytes."""
    hi, lo = s16(value)
    return lo, hi


# The AK8963 magnetometer sits behind I²C bypass as its own device at 0x0C,
# so it needs its own connection - separate from the MPU-9255's own,
# mirroring how the real driver is wired (see MPU9255Full's docstring).
connection = I2CConnectionMock()
connection.set_register(MPU9255Full._REG_WHO_AM_I, 0x73)
mag_connection = I2CConnectionMock()

sensor = MPU9255Full(connection, mag_connection)
check_true('init', True)

# init sequence: reset, wait, wake, WHO_AM_I check, default config writes.
expected_init_writes = [
    bytes([MPU9255Full._REG_PWR_MGMT_1, 0x80]),
    bytes([MPU9255Full._REG_PWR_MGMT_1, 0x01]),
    bytes([MPU9255Full._REG_WHO_AM_I]),
    bytes([MPU9255Full._REG_GYRO_CONFIG, 0x00]),
    bytes([MPU9255Full._REG_ACCEL_CONFIG, 0x00]),
    bytes([MPU9255Full._REG_ACCEL_CONFIG2, 0x03]),
    bytes([MPU9255Full._REG_CONFIG, 0x03]),
    bytes([MPU9255Full._REG_SMPLRT_DIV, 0x04]),
]
check_true('init_writes', connection.writes == expected_init_writes)

# accel(): raw (16384, -8192, 4096) at default ACCEL_FS_SEL=0 (16384 LSB/g).
connection.set_register(MPU9255Full._REG_ACCEL_XOUT_H, *s16(16384), *s16(-8192), *s16(4096))
ax, ay, az = sensor.accel()
check_true('accel_x', abs(ax - 9.80665) < 1e-9)
check_true('accel_y', abs(ay - (-4.903325)) < 1e-9)
check_true('accel_z', abs(az - 2.4516625) < 1e-9)

# gyro(): raw (131, -131, 262) at default GYRO_FS_SEL=0 (131.0 LSB/(deg/s)) -> (1, -1, 2) dps.
connection.set_register(MPU9255Full._REG_GYRO_XOUT_H, *s16(131), *s16(-131), *s16(262))
gx, gy, gz = sensor.gyro()
check_true('gyro_x', abs(gx - math.radians(1)) < 1e-9)
check_true('gyro_y', abs(gy - math.radians(-1)) < 1e-9)
check_true('gyro_z', abs(gz - math.radians(2)) < 1e-9)

sensor.configure_gyro(2)
check_true('configure_gyro_writes', connection.writes[-1] == bytes([MPU9255Full._REG_GYRO_CONFIG, 2 << 3]))
# Sensitivity for FS_SEL=2 is 32.8 LSB/(deg/s); raw=328 -> 10 dps.
connection.set_register(MPU9255Full._REG_GYRO_XOUT_H, *s16(328), *s16(0), *s16(0))
gx2, _, _ = sensor.gyro()
check_true('configure_gyro_changes_sensitivity', abs(gx2 - math.radians(10)) < 1e-9)

sensor.configure_accel(1)
check_true('configure_accel_writes', connection.writes[-1] == bytes([MPU9255Full._REG_ACCEL_CONFIG, 1 << 3]))
# Sensitivity for AFS_SEL=1 is 8192 LSB/g; raw=8192 -> 1g.
connection.set_register(MPU9255Full._REG_ACCEL_XOUT_H, *s16(8192), *s16(0), *s16(0))
ax2, _, _ = sensor.accel()
check_true('configure_accel_changes_sensitivity', abs(ax2 - 9.80665) < 1e-9)

sensor.configure_dlpf(5, 2)
check_true('configure_dlpf', connection.writes[-2:] == [
    bytes([MPU9255Full._REG_CONFIG, 5]),
    bytes([MPU9255Full._REG_ACCEL_CONFIG2, 2]),
])

sensor.configure_sample_rate(9)
check_true('configure_sample_rate', connection.writes[-1] == bytes([MPU9255Full._REG_SMPLRT_DIV, 9]))

# temperature(): raw=340 -> 340/333.87 + 21.0.
connection.set_register(MPU9255Full._REG_TEMP_OUT_H, *s16(340))
check_true('temperature', abs(sensor.temperature() - (340 / 333.87 + 21.0)) < 1e-9)

# accel_raw() / gyro_raw()
connection.set_register(MPU9255Full._REG_ACCEL_XOUT_H, *s16(100), *s16(-200), *s16(300))
check_true('accel_raw', sensor.accel_raw() == (100, -200, 300))
connection.set_register(MPU9255Full._REG_GYRO_XOUT_H, *s16(-50), *s16(60), *s16(-70))
check_true('gyro_raw', sensor.gyro_raw() == (-50, 60, -70))

# data_ready()
connection.set_register(MPU9255Full._REG_INT_STATUS, 0x01)
check_true('data_ready_true', sensor.data_ready() is True)
connection.set_register(MPU9255Full._REG_INT_STATUS, 0x00)
check_true('data_ready_false', sensor.data_ready() is False)

# set_sleep(): PWR_MGMT_1 is 0x01 in the register map after init.
sensor.set_sleep(True)
check_true('set_sleep_true', connection.writes[-1] == bytes([MPU9255Full._REG_PWR_MGMT_1, 0x41]))
sensor.set_sleep(False)
check_true('set_sleep_false', connection.writes[-1] == bytes([MPU9255Full._REG_PWR_MGMT_1, 0x01]))

# fifo_count()
connection.set_register(MPU9255Full._REG_FIFO_COUNTH, 0x03, 0x45)
check_true('fifo_count', sensor.fifo_count() == ((0x03 & 0x1F) << 8) | 0x45)

# read_fifo()
connection.set_register(MPU9255Full._REG_FIFO_COUNTH, 0x00, 0x02)
connection.set_register(MPU9255Full._REG_FIFO_R_W, 0xAA, 0xBB)
check_true('read_fifo', sensor.read_fifo() == bytes([0xAA, 0xBB]))

connection.set_register(MPU9255Full._REG_FIFO_COUNTH, 0x00, 0x00)
check_true('read_fifo_empty', sensor.read_fifo() == b'')

# enable_fifo(gyro=True, accel=True, temp=False): FIFO_EN write, then a
# USER_CTRL read (whose write-read phase also appends a bytes([reg]) entry
# to connection.writes), then the USER_CTRL write.
sensor.enable_fifo(gyro=True, accel=True, temp=False)
fifo_en_write = bytes([MPU9255Full._REG_FIFO_EN, (1 << 3) | (1 << 4)])
user_ctrl_read = bytes([MPU9255Full._REG_USER_CTRL])
user_ctrl_write = bytes([MPU9255Full._REG_USER_CTRL, 0x40])  # USER_CTRL was 0, OR 0x40
check_true('enable_fifo_writes', connection.writes[-3:] == [fifo_en_write, user_ctrl_read, user_ctrl_write])

# reset_fifo(): USER_CTRL is 0x40 in the register map after enable_fifo().
sensor.reset_fifo()
check_true('reset_fifo', connection.writes[-1] == bytes([MPU9255Full._REG_USER_CTRL, 0x44]))

# enable_mag(): INT_PIN_CFG write (on the primary connection), AK8963 CNTL1
# power-down, CNTL1 fuse ROM access, ASAX/ASAY/ASAZ reads, CNTL1 power-down,
# then CNTL1 mode write (bits=16 -> 0x10 | mode) - all on mag_connection.
mag_connection.set_register(MPU9255Full._AK8963_REG_ASAX, 200)
mag_connection.set_register(MPU9255Full._AK8963_REG_ASAY, 100)
mag_connection.set_register(MPU9255Full._AK8963_REG_ASAZ, 50)
sensor.enable_mag()
check_true('enable_mag_int_pin_cfg_write', connection.writes[-1] == bytes([MPU9255Full._REG_INT_PIN_CFG, 0x22]))
expected_mag_init_writes = [
    bytes([MPU9255Full._AK8963_REG_CNTL1, 0x00]),
    bytes([MPU9255Full._AK8963_REG_CNTL1, 0x0F]),
    bytes([MPU9255Full._AK8963_REG_ASAX]),
    bytes([MPU9255Full._AK8963_REG_ASAY]),
    bytes([MPU9255Full._AK8963_REG_ASAZ]),
    bytes([MPU9255Full._AK8963_REG_CNTL1, 0x00]),
    bytes([MPU9255Full._AK8963_REG_CNTL1, 0x16]),  # 16-bit | mode=6
]
check_true('enable_mag_writes', mag_connection.writes == expected_mag_init_writes)

# mag(): raw (1000, -500, 250) with scale factors derived from ASAX/ASAY/ASAZ
# above: (200-128)/256+1=1.28125, (100-128)/256+1=0.890625, (50-128)/256+1=0.6953125.
mag_connection.set_register(MPU9255Full._AK8963_REG_HXL,
                             *s16le(1000), *s16le(-500), *s16le(250), 0x00)
mx, my, mz = sensor.mag()
check_true('mag_x', abs(mx - (1000 * 0.15 * 1.28125)) < 1e-9)
check_true('mag_y', abs(my - (-500 * 0.15 * 0.890625)) < 1e-9)
check_true('mag_z', abs(mz - (250 * 0.15 * 0.6953125)) < 1e-9)

# mag_raw()
mag_connection.set_register(MPU9255Full._AK8963_REG_HXL,
                             *s16le(111), *s16le(-222), *s16le(333), 0x00)
check_true('mag_raw', sensor.mag_raw() == (111, -222, 333))

# configure_wake_on_motion(): PWR_MGMT_1=0x01 (clear SLEEP/CYCLE), PWR_MGMT_2=0x07
# (gyro off), ACCEL_CONFIG2=0x01 (FCHOICE_B=0, A_DLPFCFG=1), INT_ENABLE=0x40 (WOM_EN),
# MOT_DETECT_CTRL=0xC0 (hardware intel on), WOM_THR=64 mg / 4 mg = 16 LSB,
# LP_ACCEL_ODR=0x07 (31.25 Hz), then PWR_MGMT_1=0x21 (CYCLE=1).
sensor.configure_wake_on_motion(threshold_mg=64, odr_hz=31.25)
expected_wom_writes = [
    bytes([MPU9255Full._REG_PWR_MGMT_1, 0x01]),
    bytes([MPU9255Full._REG_PWR_MGMT_2, 0x07]),
    bytes([MPU9255Full._REG_ACCEL_CONFIG2, 0x01]),
    bytes([MPU9255Full._REG_INT_ENABLE, 0x40]),
    bytes([MPU9255Full._REG_MOT_DETECT_CTRL, 0xC0]),
    bytes([MPU9255Full._REG_WOM_THR, 16]),
    bytes([MPU9255Full._REG_LP_ACCEL_ODR, 0x07]),
    bytes([MPU9255Full._REG_PWR_MGMT_1, 0x21]),
]
check_true('configure_wake_on_motion_writes', connection.writes[-8:] == expected_wom_writes)

# motion_detected(): True when WOM_INT (bit 6) set in INT_STATUS.
connection.set_register(MPU9255Full._REG_INT_STATUS, 0x40)
check_true('motion_detected_true', sensor.motion_detected() is True)
connection.set_register(MPU9255Full._REG_INT_STATUS, 0x00)
check_true('motion_detected_false', sensor.motion_detected() is False)

# threshold_mg clamping: 0 -> 4 mg (1 LSB), 4000 -> 1020 mg (255 LSB).
connection.writes = []
sensor.configure_wake_on_motion(threshold_mg=0, odr_hz=31.25)
check_true('wom_threshold_low_clamped', connection.writes[5] == bytes([MPU9255Full._REG_WOM_THR, 1]))
connection.writes = []
sensor.configure_wake_on_motion(threshold_mg=4000, odr_hz=31.25)
check_true('wom_threshold_high_clamped', connection.writes[5] == bytes([MPU9255Full._REG_WOM_THR, 255]))

# mag() / mag_raw() before enable_mag() raise.
unenabled_connection = I2CConnectionMock()
unenabled_connection.set_register(MPU9255Full._REG_WHO_AM_I, 0x73)
unenabled_sensor = MPU9255Full(unenabled_connection, I2CConnectionMock())
try:
    unenabled_sensor.mag()
    check_true('mag_not_enabled_raises', False)
except RuntimeError:
    check_true('mag_not_enabled_raises', True)
try:
    unenabled_sensor.mag_raw()
    check_true('mag_raw_not_enabled_raises', False)
except RuntimeError:
    check_true('mag_raw_not_enabled_raises', True)

# WHO_AM_I mismatch raises during construction.
bad_connection = I2CConnectionMock()
bad_connection.set_register(MPU9255Minimal._REG_WHO_AM_I, 0x00)
try:
    MPU9255Minimal(bad_connection)
    check_true('who_am_i_mismatch_raises', False)
except ValueError:
    check_true('who_am_i_mismatch_raises', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)