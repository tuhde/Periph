from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ina226 import INA226Minimal
import time

connection = I2CConnection(0x40)
ina = INA226Minimal(connection)

while True:
    print(ina.voltage(), ina.current(), ina.power())
    time.sleep(1)
