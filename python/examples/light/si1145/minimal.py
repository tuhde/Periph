from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.si1145 import Si1145Minimal
import time

connection = I2CConnection(0x60)                                         # Create I²C connection, (addr=0x60) → Connection
sensor = Si1145Minimal(connection)                                       # Create Si1145 driver, (connection) → None
sensor.init()                                                            # Check PART_ID, HW_KEY, UV + ALS + PS1, () → None

while True:
    m = sensor.read()                                                    # Forced PS + ALS, () → dict {proximity, uv_index, visible, infrared}
    print('prox %5d  UV %.2f  vis %5d  ir %5d'
          % (m['proximity'], m['uv_index'], m['visible'], m['infrared']))
    time.sleep(1)
