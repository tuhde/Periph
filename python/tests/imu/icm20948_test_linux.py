import time
import sys
import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.imu.icm20948 import ICM20948Full

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x68'), 16)

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
imu = ICM20948Full(connection)

check_eq('who_am_i', connection.read_reg(0x00, 1)[0], 0xEA)

ax, ay, az = imu.accel()
check_true('accel_x finite', -200.0 < ax < 200.0)
check_true('accel_y finite', -200.0 < ay < 200.0)
check_true('accel_z finite', -200.0 < az < 200.0)
check_true('gravity magnitude', 5.0 < (ax * ax + ay * ay + az * az) ** 0.5 < 15.0)

gx, gy, gz = imu.gyro()
check_true('gyro_x finite', -100.0 < gx < 100.0)
check_true('gyro_y finite', -100.0 < gy < 100.0)
check_true('gyro_z finite', -100.0 < gz < 100.0)

t = imu.temperature()
check_true('temperature range', -40.0 < t < 85.0)

accel, gyro, temp = imu.read_all()
check_true('read_all temp range', -40.0 < temp < 85.0)

rax, ray, raz = imu.accel_raw()
check_true('accel_raw_x range', -32768 <= rax <= 32767)
rgx, rgy, rgz = imu.gyro_raw()
check_true('gyro_raw_x range', -32768 <= rgx <= 32767)

imu.configure_gyro(1)
imu.configure_accel(1)
ax2, ay2, az2 = imu.accel()
check_true('accel after reconfig', -200.0 < ax2 < 200.0)

imu.configure_filters(4, 4)
imu.configure_sample_rate(9, 9)
check_eq('bank 0 after config', connection.read_reg(0x00, 1)[0], 0xEA)

ox, oy, oz = imu.get_accel_offset()
check_true('accel offset range', -16384 <= ox <= 16383)

imu.enable_mag()
time.sleep(0.15)
mx, my, mz = imu.mag()
check_true('mag magnitude', 5.0 < (mx * mx + my * my + mz * mz) ** 0.5 < 150.0)
check_true('aux_read WIA2', imu.aux_read_byte(0x0C, 0x01) == 0x09)

imu.set_sleep(True)
time.sleep(0.01)
imu.set_sleep(False)
time.sleep(0.05)
ax3, ay3, az3 = imu.accel()
check_true('accel after wake', -200.0 < ax3 < 200.0)

imu.enable_fifo(accel=True, gyro=True)
time.sleep(0.05)
count = imu.fifo_count()
check_true('fifo_count > 0', count > 0)
data = imu.read_fifo()
check_true('read_fifo returns bytes', len(data) > 0)
imu.reset_fifo()

imu.set_wake_on_motion(200)
status = imu.poll_interrupt()
check_true('poll_interrupt int', isinstance(status, int))

connection.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
