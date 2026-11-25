from periph.connection.i2c_auto import I2CConnection
from periph.chips.humidity.si7021 import Si7021Minimal
import time

connection = I2CConnection(0x40)                                         # Create I²C connection, (addr=0x40) → Connection
sensor = Si7021Minimal(connection)                                       # Create Si7021 driver, (connection) → None

while True:
    t, rh = sensor.read()                                                # Read temperature and humidity, () → (float °C, float %RH)
    print('%.2f C, %.1f %%RH' % (t, rh))
    time.sleep(2)
