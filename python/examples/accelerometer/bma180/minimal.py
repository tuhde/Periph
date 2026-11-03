"""BMA180 minimal example — read X, Y, Z acceleration in *g* in a tight loop.
Tier-1 signature comments on every call."""

import time
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.bma180 import BMA180Minimal
from machine import I2C, Pin

# Wiring: I2C(0, sda=Pin(21), scl=Pin(22), freq=400000)
i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400000)
connection = I2CConnection(i2c, 0x40)
chip = BMA180Minimal(connection)                       # Create BMA180 driver, (connection)
while True:
    x, y, z = chip.read()                              # Read 3-axis acceleration, () → tuple g
    print("x={:.3f} y={:.3f} z={:.3f}".format(x, y, z))
    time.sleep_ms(100)