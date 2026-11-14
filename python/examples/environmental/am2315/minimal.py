from periph.connection.i2c_auto import I2CConnection
from periph.chips.environmental.am2315 import AM2315Minimal
import time

connection = I2CConnection(0x5C)                                         # Create I²C connection, (addr=0x5C) → Connection
sensor = AM2315Minimal(connection)                                       # Create AM2315 driver, (connection) → None

while True:
    r = sensor.read()                                                    # Read temperature and humidity, () → dict {temperature_c, humidity_pct}
    print('%.1f C, %.1f %%RH' % (r['temperature_c'], r['humidity_pct']))
    time.sleep(2)
