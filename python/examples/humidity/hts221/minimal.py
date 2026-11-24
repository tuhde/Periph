from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hts221 import HTS221Minimal
import time

connection = I2CConnection(0x5F)                                         # Create I²C connection, (addr=0x5F) → Connection
sensor = HTS221Minimal(connection)                                       # Create HTS221 driver, (connection) → None

while True:
    m = sensor.read()                                                    # Read temperature and humidity, () → dict {temperature_c °C, humidity_pct %rH}
    print('%.2f C, %.1f %%rH' % (m['temperature_c'], m['humidity_pct']))
    time.sleep(2)
