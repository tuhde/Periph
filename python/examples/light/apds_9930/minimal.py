"""Minimal example for the APDS-9930 ambient light + proximity sensor.

Constructs the chip with default settings and prints lux + proximity
once a second.
"""

import time

from periph.chips.light.apds_9930 import APDS9930Minimal
from periph.connection.i2c_auto import I2CConnection

connection = I2CConnection(0x39)                                       # Create I2C connection, (addr=0x39, bus=None) → I2CConnection
apds = APDS9930Minimal(connection)                                     # Construct APDS-9930 Minimal, (connection) → APDS9930Minimal
                                                                     # initialises with ATIME=0xDB, PTIME=0xFF, PPULSE=8, CONTROL=0x20
time.sleep(0.110)
while True:
    lx = apds.lux()                                                   # Read ambient illuminance, () → float lx
                                                                     # converts Ch0/Ch1 ADC counts to lux using IR-compensated formula
    p = apds.proximity()                                              # Read proximity count, () → int count
                                                                     # 16-bit ADC value; higher = closer object
    print('lux={:.1f}  proximity={}'.format(lx, p))
    time.sleep(1.0)