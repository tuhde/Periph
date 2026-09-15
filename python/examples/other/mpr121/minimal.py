"""Minimal example for the MPR121 capacitive touch controller.

Constructs the chip with the default touch/release thresholds and
prints the 12-bit touched bitmask once a second. ELE0 is mapped to
the least-significant bit.
"""

import time

from periph.chips.other.mpr121 import Mpr121Minimal
from periph.connection.i2c_auto import I2CConnection

connection = I2CConnection(0x5A)                                         # Create I2C connection, (addr=0x5A, bus=None) → I2CConnection
mpr = Mpr121Minimal(connection)                                          # Construct MPR121 Minimal, (connection) → Mpr121Minimal
                                                                     # resets, applies default thresholds (T=12, R=6), enters Run Mode on all 12 electrodes

while True:
    t = mpr.touched()                                                    # Read 12-bit touch bitmask, () → int bitmask
                                                                     # bit n=1 means ELEn is currently touched
    print('touched=0x{:03X}'.format(t))
    time.sleep(1.0)
