from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.bh1750 import BH1750Minimal
import time

connection = I2CConnection(0x23)                                         # Create I²C connection, (addr=0x23) → Connection
sensor = BH1750Minimal(connection)                                       # Create BH1750 driver, (connection) → None
sensor.init()                                                            # Power on, () → None

while True:
    print('%.1f lx' % sensor.read())                                    # Measure illuminance, () → float lx
    time.sleep(1)
