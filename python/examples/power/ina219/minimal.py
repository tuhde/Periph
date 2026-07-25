from periph.transport.i2c_auto import I2CTransport
from periph.chips.power.ina219 import INA219Minimal
import time

transport = I2CTransport(0x40)
ina = INA219Minimal(transport)

while True:
    v = ina.voltage()                                  # Read bus voltage, () → float V
    i = ina.current()                                  # Read load current, () → float A
    p = ina.power()                                    # Read power, () → float W
    print(v, i, p)
    time.sleep(1)
