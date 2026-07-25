from periph.transport.i2c_auto import I2CTransport
from periph.chips.power.ina226 import INA226Minimal
import time

transport = I2CTransport(0x40)
ina = INA226Minimal(transport)

while True:
    print(ina.voltage(), ina.current(), ina.power())
    time.sleep(1)
