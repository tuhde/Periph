from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.spl06 import SPL06Minimal
import time

connection = I2CConnection(0x77)                                         # Create I²C connection, (addr=0x77) → Connection
sensor = SPL06Minimal(connection)                                        # Create SPL06 driver, (connection) → None
sensor.init()                                                            # Check ID, read coefficients, apply defaults, () → None

while True:
    m = sensor.read()                                                    # Measure temperature and pressure, () → dict {temperature_c °C, pressure_pa Pa}
    print('%.2f Pa, %.2f C' % (m['pressure_pa'], m['temperature_c']))
    time.sleep(1)
