from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina226 import INA226Minimal
import time

i2c = I2C(1, freq=400000)
transport = I2CTransport(i2c, 0x40)
ina = INA226Minimal(transport)

while True:
    print(ina.voltage(), ina.current(), ina.power())
    time.sleep(1)
