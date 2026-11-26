from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.si1133 import Si1133Minimal
import time

connection = I2CConnection(0x55)                                         # Create I²C connection, (addr=0x55) → Connection
sensor = Si1133Minimal(connection)                                       # Create Si1133 driver, (connection) → None
sensor.init()                                                            # Check PART_ID, reset, UV + white channels, () → None

while True:
    m = sensor.read()                                                    # Forced measurement, () → dict {uv counts, white counts}
    print('UV %d, white %d' % (m['uv'], m['white']))
    time.sleep(1)
