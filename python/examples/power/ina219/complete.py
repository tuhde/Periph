from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina219 import INA219Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x40)
ina = INA219Full(transport)

print(ina.voltage())
print(ina.shunt_voltage())
print(ina.current())
print(ina.power())
print(ina.conversion_ready())
print(ina.overflow())

ina.configure(brng=1, pga=3, badc=3, sadc=3, mode=7)

print(ina.conversion_ready())
print(ina.overflow())

ina.shutdown()
time.sleep_ms(1)
ina.wake()

ina.trigger()

ina.reset()