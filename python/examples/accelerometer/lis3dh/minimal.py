"""Minimal example for the LIS3DH — read X, Y, Z acceleration in a loop.

Constructs the driver with the defaults (±2 g, 100 Hz, high-resolution
12-bit mode, BDU on) and prints each sample to stdout.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lis3dh import LIS3DHMinimal

# I²C bus on most ESP32 / Pi Pico dev boards; adjust for your wiring.
i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x18)                      # Create I²C connection, (i2c, addr=0x18)
accel = LIS3DHMinimal(connection)                          # Create LIS3DH driver, (connection)

for _ in range(10):
    x, y, z = accel.read()                                # Read 3-axis acceleration, () → tuple(float, float, float) g
    print('x={:.3f} y={:.3f} z={:.3f} g'.format(x, y, z))
