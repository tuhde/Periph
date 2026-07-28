from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ina3221 import INA3221Minimal
import time

connection = I2CConnection(0x40)
ina = INA3221Minimal(connection)                       # Create INA3221 driver, (connection, r_shunt=0.1 Ω)

while True:
    for ch in (1, 2, 3):
        v = ina.voltage(ch)                            # Read bus voltage, (channel) → float V
        i = ina.current(ch)                            # Read load current, (channel) → float A
        p = ina.power(ch)                              # Read power, (channel) → float W
        print('ch{}: {:.3f}V {:.4f}A {:.4f}W'.format(ch, v, i, p))
    time.sleep(1)