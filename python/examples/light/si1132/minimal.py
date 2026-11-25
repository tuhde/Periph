"""Minimal example for the Si1132 — read UV index, visible and infrared counts.

Constructs the driver with its defaults (forced mode, gain 0, all three
channels) and prints one reading per second.
"""

from periph.connection.i2c_auto import I2CConnection
from periph.chips.light.si1132 import Si1132Minimal
import time

connection = I2CConnection(0x60)                         # Create I²C connection, (addr=0x60) → Connection
sensor = Si1132Minimal(connection)                       # Create Si1132 driver, (connection) → None

while True:
    m = sensor.read()                                    # Read UV index and ALS counts, () → dict {uv_index, visible counts, infrared counts}
    print('UV %.2f  visible %d  infrared %d' % (m['uv_index'], m['visible'], m['infrared']))
    time.sleep(1)
