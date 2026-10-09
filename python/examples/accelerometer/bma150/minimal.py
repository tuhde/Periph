"""Minimal example for the BMA150 — read X, Y, Z acceleration in a loop.

Constructs the driver with the defaults (range ±2 g, bandwidth 100 Hz,
preserving factory calibration bits) and prints each sample to stdout.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.bma150 import BMA150Minimal

# I²C bus on most ESP32 / Pi Pico dev boards; adjust for your wiring.
i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x38)
accel = BMA150Minimal(connection)                          # Create BMA150 driver, (connection)

for _ in range(10):
    x, y, z = accel.read()                                # Read 3-axis acceleration, () → tuple(float, float, float) g
    print('x={:.3f} y={:.3f} z={:.3f} g'.format(x, y, z))
