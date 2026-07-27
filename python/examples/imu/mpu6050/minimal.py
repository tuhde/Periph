from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.mpu6050 import MPU6050Minimal
import time

connection = I2CConnection(0x68)
imu = MPU6050Minimal(connection)                          # Create MPU6050 driver, (connection) → None

while True:
    ax, ay, az = imu.accel()                             # Read 3-axis acceleration, () → (float, float, float) m/s²
    gx, gy, gz = imu.gyro()                              # Read 3-axis angular rate, () → (float, float, float) rad/s
    print('accel: %.2f %.2f %.2f  gyro: %.2f %.2f %.2f' % (ax, ay, az, gx, gy, gz))
    time.sleep_ms(100)
