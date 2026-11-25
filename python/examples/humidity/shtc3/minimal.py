from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.shtc3 import SHTC3Minimal
import time

connection = I2CConnection(0x70)                                         # Create I²C connection, (addr=0x70) → Connection
sensor = SHTC3Minimal(connection)                                        # Create SHTC3 driver, (connection) → None
sensor.init()                                                            # Soft reset and ID check, () → None

while True:
    m = sensor.read()                                                    # Read temperature and humidity, () → dict {temperature_c °C, humidity_pct %RH}
    print('%.2f C, %.1f %%RH' % (m['temperature_c'], m['humidity_pct']))
    time.sleep(2)
