from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina219 import INA219Minimal
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x40)
ina = INA219Minimal(transport)

while True:
    print(ina.voltage(), ina.shunt_voltage(), ina.current(), ina.power())
    time.sleep(1)