import os
import sys
import time

from periph.connection.i2c_linux import I2CConnection
from periph.chips.imu.qmi8658a import QMI8658AFull

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x6B'), 16)

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


connection = I2CConnection(I2C_BUS, I2C_ADDR)
imu = QMI8658AFull(connection)

check_eq('who_am_i', connection.read_reg(0x00, 1)[0], 0x05)

ax, ay, az = imu.accel()
check_true('accel magnitude ~1 g', 7.0 < (ax * ax + ay * ay + az * az) ** 0.5 < 12.6)
gx, gy, gz = imu.gyro()
check_true('gyro finite', abs(gx) < 10.0 and abs(gy) < 10.0 and abs(gz) < 10.0)

accel, gyro, t = imu.read_all()
check_true('temperature range', -40.0 < t < 85.0)
check_true('temperature matches', abs(imu.temperature() - t) < 5.0)
rax, ray, raz = imu.accel_raw()
check_true('accel_raw range', -32768 <= rax <= 32767)
rgx, rgy, rgz = imu.gyro_raw()
check_true('gyro_raw range', -32768 <= rgx <= 32767)

ts1 = imu.timestamp()
time.sleep(0.1)
check_true('timestamp advances', imu.timestamp() != ts1)

imu.configure_accel(1, 5)
imu.configure_gyro(5, 5)
time.sleep(0.2)
ax2, ay2, az2 = imu.accel()
check_true('accel after reconfig', 7.0 < (ax2 * ax2 + ay2 * ay2 + az2 * az2) ** 0.5 < 12.6)
imu.configure_accel()
imu.configure_gyro()
imu.configure_filters(True, 3, True, 3)
time.sleep(0.2)

imu.configure_fifo(2, 1, 8)
time.sleep(0.3)
count = imu.fifo_count()
check_true('fifo_count non-zero', count > 0)
data = imu.read_fifo()
check_true('read_fifo returns whole frames', len(data) > 0 and len(data) % 12 == 0)
imu.reset_fifo()
imu.configure_fifo(0)
time.sleep(0.1)

info = imu.device_info()
check_true('device_info firmware', len(info['firmware']) == 3 and len(info['usid']) == 6)
imu.set_sensors(True, True)

connection.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
