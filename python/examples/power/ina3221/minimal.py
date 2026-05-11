from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina3221 import INA3221Minimal
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x40)
ina = INA3221Minimal(transport)                       # Create INA3221 driver, (transport, r_shunt=0.1 Ω)

while True:
    for ch in (1, 2, 3):
        v = ina.voltage(ch)                            # Read bus voltage, (channel) → float V
        i = ina.current(ch)                            # Read load current, (channel) → float A
        p = ina.power(ch)                              # Read power, (channel) → float W
        print('ch{}: {:.3f}V {:.4f}A {:.4f}W'.format(ch, v, i, p))
    time.sleep(1)