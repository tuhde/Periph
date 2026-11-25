from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hdc1080 import HDC1080Minimal
import time

connection = I2CConnection(0x40)                                         # Create I²C connection, (addr=0x40) → Connection
sensor = HDC1080Minimal(connection)                                      # Create HDC1080 driver, (connection) → None
sensor.init()                                                            # Write default configuration, () → None

while True:
    t, rh = sensor.read()                                                # Read temperature and humidity, () → (float °C, float %RH)
    print('%.2f C, %.1f %%RH' % (t, rh))
    time.sleep(2)
