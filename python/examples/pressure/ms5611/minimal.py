from periph.connection.i2c_auto import I2CConnection
from periph.chips.pressure.ms5611 import MS5611Minimal
import time

connection = I2CConnection(0x77)                                         # Create I²C connection, (addr=0x77) → Connection
sensor = MS5611Minimal(connection)                                       # Create MS5611 driver, (connection) → None
sensor.init()                                                            # Reset, read PROM, verify CRC-4, () → None

while True:
    m = sensor.read()                                                    # Read temperature and pressure, () → dict {temperature_c °C, pressure_pa Pa}
    print('%.2f C, %.1f Pa' % (m['temperature_c'], m['pressure_pa']))
    time.sleep(1)
