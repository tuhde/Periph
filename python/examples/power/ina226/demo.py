from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina226 import INA226Full
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x40)
ina = INA226Full(transport)

# 64-sample averaging smooths out switching noise from DC/DC converters
ina.configure(avg=3, vbus_ct=4, vsh_ct=4, mode=7)

# latch the alert so a brief spike is not missed between loop iterations
ina.set_alert(INA226Full.POL, limit=1.0, latch=1)

print('%-10s %-10s %-10s' % ('V', 'A', 'W'))
while True:
    # wait for a fresh conversion to avoid reading stale register values
    while not ina.conversion_ready():
        pass

    v = ina.voltage()
    i = ina.current()
    p = ina.power()
    print('%-10.3f %-10.4f %-10.4f' % (v, i, p))

    # reading alert_flags clears the latch — do this after printing measurements
    if ina.alert_flags() & INA226Full.AFF:
        print('ALERT: power limit exceeded')

    time.sleep(1)
