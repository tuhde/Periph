from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.apds9960 import APDS9960Minimal
import time

connection = I2CConnection(0x39)
apds = APDS9960Minimal(connection)                          # Create APDS9960 driver, (connection) → APDS9960Minimal

while True:
    c, r, g, b = apds.color()                              # Read all RGBC channels, () → tuple(int, int, int, int)
    print('C=%d R=%d G=%d B=%d' % (c, r, g, b))
    time.sleep(1)
