from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ina219 import INA219Minimal
import time

connection = I2CConnection(0x40)
ina = INA219Minimal(connection)

while True:
    v = ina.voltage()                                  # Read bus voltage, () → float V
    i = ina.current()                                  # Read load current, () → float A
    p = ina.power()                                    # Read power, () → float W
    print(v, i, p)
    time.sleep(1)
