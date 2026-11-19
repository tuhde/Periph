from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hs40xx import HS40xxMinimal
import time

connection = I2CConnection(0x54)                                         # Create I²C connection, (addr=0x54) → Connection
sensor = HS40xxMinimal(connection)                                       # Create HS40xx driver, (connection) → None

while True:
    d = sensor.read()                                                    # Read temperature and humidity, () → dict {temperature_c, humidity_pct}
    print('%.2f C, %.1f %%RH' % (d['temperature_c'], d['humidity_pct']))
    time.sleep(2)
