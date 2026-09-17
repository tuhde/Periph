from periph.connection.i2c_auto import I2CConnection
from periph.chips.magnetometer.hmc5883l import HMC5883LMinimal
import time

connection = I2CConnection(0x1E)
hmc5883l = HMC5883LMinimal(connection)                     # Create HMC5883L driver, (connection) → HMC5883LMinimal

while True:
    x, y, z = hmc5883l.magnetic_field()                    # Read magnetic field, () → (float T, float T, float T)
    print('X=%.6f T  Y=%.6f T  Z=%.6f T' % (x, y, z))
    time.sleep(1)