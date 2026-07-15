from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.imu.mpu6050 import MPU6050Minimal
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x68)
imu = MPU6050Minimal(transport)                          # Create MPU6050 driver, (transport) → None

while True:
    ax, ay, az = imu.accel()                             # Read 3-axis acceleration, () → (float, float, float) m/s²
    gx, gy, gz = imu.gyro()                              # Read 3-axis angular rate, () → (float, float, float) rad/s
    print('accel: %.2f %.2f %.2f  gyro: %.2f %.2f %.2f' % (ax, ay, az, gx, gy, gz))
    time.sleep_ms(100)
