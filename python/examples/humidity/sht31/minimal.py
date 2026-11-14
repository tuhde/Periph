from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.sht31 import SHT31Minimal
import time

connection = I2CConnection(0x44)                                         # Create I²C connection, (addr=0x44) → Connection
sensor = SHT31Minimal(connection)                                        # Create SHT31 driver, (connection) → None

while True:
    t, rh = sensor.read()                                                # Read temperature and humidity, () → (float °C, float %RH)
    print('%.2f C, %.1f %%RH' % (t, rh))
    time.sleep(2)
