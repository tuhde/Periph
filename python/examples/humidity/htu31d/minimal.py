from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.htu31d import HTU31DMinimal
import time

connection = I2CConnection(0x40)                                         # Create I²C connection, (addr=0x40) → Connection
sensor = HTU31DMinimal(connection)                                       # Create HTU31D driver, (connection) → None
sensor.init()                                                            # Soft reset, () → None

while True:
    m = sensor.read()                                                    # Read temperature and humidity, () → dict {temperature_c °C, humidity_pct %RH}
    print('%.2f C, %.1f %%RH' % (m['temperature_c'], m['humidity_pct']))
    time.sleep(2)
