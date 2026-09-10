"""Minimal example for the ADXL345 — read X, Y, Z acceleration in a loop.

Constructs the driver with the defaults (full-resolution, ±2 g, 100 Hz) and
prints each sample to stdout. Designed to be the smallest possible program
that proves the wiring and bus are working.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.adxl345 import ADXL345Minimal

# I²C bus on most ESP32 / Pi Pico dev boards; adjust for your wiring.
i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x53)
accel = ADXL345Minimal(connection)                      # Create ADXL345 driver, (connection, bus_type='i2c')

for _ in range(10):
    x, y, z = accel.read()                             # Read 3-axis acceleration, () → tuple(float, float, float) g
    print('x={:.3f} y={:.3f} z={:.3f} g'.format(x, y, z))