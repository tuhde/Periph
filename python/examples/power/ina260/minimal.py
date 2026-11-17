from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ina260 import INA260Minimal
import time

connection = I2CConnection(0x40)                                       # Create I²C connection, (addr=0x40) → Connection
ina = INA260Minimal(connection)                                        # Create INA260 driver, (connection) → None

while True:
    print(ina.voltage(), ina.current(), ina.power())                   # Read V / A / W, () → float each
    time.sleep(1)
