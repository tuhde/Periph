import sys
import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.imu.bno055 import BNO055Full

from machine import I2C, Pin

passed = 0
failed = 0

def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got 0x{:02X}, expected 0x{:02X}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)
imu = BNO055Full(connection)

check_eq('chip_id', connection.read_reg(0x00, 1)[0], 0xA0)

info = imu.revisions()
check_eq('acc_id', info['acc_id'], 0xFB)
check_eq('mag_id', info['mag_id'], 0x32)
check_eq('gyr_id', info['gyr_id'], 0x0F)
check_eq('mode_ndof', imu.get_mode(), 0x0C)

h, r, p = imu.euler()
check_true('euler range', 0.0 <= h < 6.5 and -3.2 < p < 3.2 and -1.6 < r < 1.6)
w, x, y, z = imu.quaternion()
check_true('quaternion unit', abs((w * w + x * x + y * y + z * z) ** 0.5 - 1.0) < 0.05)
gx, gy, gz = imu.gravity()
check_true('gravity ~1 g', 8.5 < (gx * gx + gy * gy + gz * gz) ** 0.5 < 11.0)
lx, ly, lz = imu.linear_acceleration()
check_true('linear acceleration small', abs(lx) < 5.0 and abs(ly) < 5.0 and abs(lz) < 5.0)
check_true('temperature range', -40 < imu.temperature() < 85)
check_true('calibration status', all(0 <= v <= 3 for v in imu.calibration_status()))
check_true('system status', imu.system_status() == (5, 0))
check_eq('unique_id length', len(imu.unique_id()), 16)
check_eq('calibration length', len(imu.get_calibration()), 22)

imu.set_mode(BNO055Full.MODE_AMG)
check_eq('mode_amg', imu.get_mode(), 0x07)
ax, ay, az = imu.acceleration()
check_true('accel magnitude ~1 g', 8.0 < (ax * ax + ay * ay + az * az) ** 0.5 < 11.5)
mx, my, mz = imu.magnetic_field()
check_true('mag field range', 5.0 < (mx * mx + my * my + mz * mz) ** 0.5 < 200.0)
imu.set_mode(BNO055Full.MODE_NDOF)
check_eq('mode_ndof_again', imu.get_mode(), 0x0C)

st = imu.self_test()
check_true('self_test passes', st['acc'] and st['mag'] and st['gyr'] and st['mcu'])
check_eq('mode after self_test', imu.get_mode(), 0x0C)

imu.enable_interrupt(BNO055Full.SOURCE_ACC_BSX_DRDY)
time.sleep(0.05)
check_true('poll_interrupt ok', 0 <= imu.poll_interrupt() <= 0xFF)
imu.disable_interrupt(BNO055Full.SOURCE_ACC_BSX_DRDY)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
