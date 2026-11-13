"""Minimal example for the PCA9541 — take and release the downstream I²C bus.

Constructs the driver (it writes nothing), takes the downstream bus, reports
whether this master has control and gives the bus to the other master.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.other.pca9541 import PCA9541Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
selector = PCA9541Minimal(connection)                    # Create PCA9541 driver, (connection)

print(selector.has_control())                            # Report control, () → bool
if selector.acquire():                                   # Take the downstream bus, (bus_init=False, timeout_ms=20) → bool
    print('downstream bus is ours')
    time.sleep(1)
selector.release()                                       # Give the bus to the other master, () → None
print(selector.has_control())
