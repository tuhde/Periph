from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.mpl3115a2 import MPL3115A2Minimal
import time

connection = I2CConnection(0x60)                                         # Create I²C connection, (addr=0x60) → Connection
sensor = MPL3115A2Minimal(connection)                                    # Create MPL3115A2 driver, (connection) → None
sensor.init()                                                            # Reset and apply defaults, () → None

while True:
    m = sensor.read()                                                    # Read pressure and temperature, () → dict {pressure_pa Pa, temperature_c °C}
    print('%.2f Pa, %.2f C' % (m['pressure_pa'], m['temperature_c']))
    time.sleep(2)
