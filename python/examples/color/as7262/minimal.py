from periph.connection.i2c_auto import I2CConnection
from periph.chips.color.as7262 import AS7262Minimal
import time

connection = I2CConnection(0x49)                                         # Create I²C connection, (addr=0x49) → Connection
sensor = AS7262Minimal(connection)                                       # Create AS7262 driver, (connection) → None

while True:
    d = sensor.read()                                                    # Read six calibrated channels, () → dict {violet, blue, green, yellow, orange, red}
    print(d)
    print('%.0f C' % sensor.temperature())                               # Read device temperature, () → float °C
    time.sleep(2)
