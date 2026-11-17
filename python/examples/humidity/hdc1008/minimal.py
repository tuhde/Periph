from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.hdc1008 import HDC1008Minimal
import time

connection = I2CConnection(0x40)                                         # Create I²C connection, (addr=0x40) → Connection
sensor = HDC1008Minimal(connection)                                      # Create HDC1008 driver, (connection) → None
sensor.init()                                                            # Write default configuration, () → None

while True:
    t, rh = sensor.read()                                                # Read temperature and humidity, () → (float °C, float %RH)
    print('%.2f C, %.1f %%RH' % (t, rh))
    time.sleep(2)
