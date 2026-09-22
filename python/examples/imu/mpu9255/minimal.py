from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.mpu9255 import MPU9255Minimal
import time

connection = I2CConnection(0x68)
imu = MPU9255Minimal(connection)                           # Create MPU9255 driver, (connection) → None

while True:
    ax, ay, az = imu.accel()                              # Read 3-axis acceleration, () → (float, float, float) m/s²
    gx, gy, gz = imu.gyro()                               # Read 3-axis angular rate, () → (float, float, float) rad/s
    print('accel: %.2f %.2f %.2f  gyro: %.2f %.2f %.2f' % (ax, ay, az, gx, gy, gz))
    time.sleep_ms(100)