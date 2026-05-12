from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina219 import INA219Minimal
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x40)
ina = INA219Minimal(transport)

while True:
    v = ina.voltage()                                  # Read bus voltage, () → float V
    i = ina.current()                                  # Read load current, () → float A
    p = ina.power()                                    # Read power, () → float W
    print(v, i, p)
    time.sleep(1)
