from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.bno055 import BNO055Minimal
import time

connection = I2CConnection(0x29)
imu = BNO055Minimal(connection)                            # Create BNO055 driver, (connection) → None

while True:
    heading, roll, pitch = imu.euler()                     # Read fused Euler angles, () → (float, float, float) rad
    print('heading %.2f rad, roll %.2f, pitch %.2f' % (heading, roll, pitch))
    time.sleep(0.1)
