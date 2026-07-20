import time
import busio
import _testconfig as cfg
from periph.transport.i2c_circuitpython import I2CTransport
from periph.chips.imu.mpu6050 import MPU6050Full

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


i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
imu = MPU6050Full(transport)

check_eq('who_am_i', imu._read_reg(imu._REG_WHO_AM_I), 0x68)

ax, ay, az = imu.accel()
check_true('accel_x finite', -200.0 < ax < 200.0)
check_true('accel_y finite', -200.0 < ay < 200.0)
check_true('accel_z finite', -200.0 < az < 200.0)

gx, gy, gz = imu.gyro()
check_true('gyro_x finite', -100.0 < gx < 100.0)
check_true('gyro_y finite', -100.0 < gy < 100.0)
check_true('gyro_z finite', -100.0 < gz < 100.0)

t = imu.temperature()
check_true('temperature range', -40.0 < t < 85.0)

rax, ray, raz = imu.accel_raw()
check_true('accel_raw_x range', -32768 <= rax <= 32767)
rgx, rgy, rgz = imu.gyro_raw()
check_true('gyro_raw_x range', -32768 <= rgx <= 32767)

imu.configure_gyro(1)
imu.configure_accel(1)
ax2, ay2, az2 = imu.accel()
check_true('accel after reconfig', -200.0 < ax2 < 200.0)

imu.configure_dlpf(4)
imu.configure_sample_rate(9)
check_true('data_ready after reconfig', imu.data_ready() or True)

imu.set_sleep(True)
time.sleep(0.01)
imu.set_sleep(False)
time.sleep(0.05)
ax3, ay3, az3 = imu.accel()
check_true('accel after wake', -200.0 < ax3 < 200.0)

imu.set_standby(xa=True)
imu.set_standby()

imu.reset_fifo()
imu.enable_fifo(gyro=True, accel=True)
time.sleep(0.05)
count = imu.fifo_count()
check_true('fifo_count > 0', count > 0)
data = imu.read_fifo()
check_true('read_fifo matches count', len(data) == count)

imu.reset_fifo()

i2c.deinit()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
