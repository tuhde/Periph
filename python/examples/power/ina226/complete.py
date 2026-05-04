from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina226 import INA226Full
import time

i2c = I2C(1, freq=400000)
transport = I2CTransport(i2c, 0x40)
ina = INA226Full(transport)

print(hex(ina.manufacturer_id()))
print(hex(ina.die_id()))

print(ina.voltage())
print(ina.shunt_voltage())
print(ina.current())
print(ina.power())
print(ina.conversion_ready())
print(ina.overflow())

ina.configure(avg=3, vbus_ct=4, vsh_ct=4, mode=7)

ina.set_alert(INA226Full.POL, limit=1.0, latch=1)
print(hex(ina.alert_flags()))

ina.set_alert(INA226Full.BOL, limit=5.5)
ina.set_alert(INA226Full.BUL, limit=4.5)
ina.set_alert(INA226Full.SOL, limit=0.05)
ina.set_alert(INA226Full.CNVR)

ina.shutdown()
time.sleep_ms(1)
ina.wake()

ina.reset()
