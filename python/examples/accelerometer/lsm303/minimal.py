"""Minimal example for the LSM303 — read acceleration and magnetic field.

Constructs the driver with the defaults (±2 g, 100 Hz, high-resolution,
±130 µT, 15 Hz, continuous) and prints each sample to stdout. The module
presents two I²C slaves — accelerometer 0x19 and magnetometer 0x1E — so two
connections to the same bus are required.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Minimal

# I²C bus on most ESP32 / Pi Pico dev boards; adjust for your wiring.
i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
accel_conn = I2CConnection(i2c, 0x19)
mag_conn = I2CConnection(i2c, 0x1E)
compass = LSM303Minimal(accel_conn, mag_conn)           # Create LSM303 driver, (accel_connection, mag_connection)

for _ in range(10):
    x, y, z = compass.read_acceleration()              # Read 3-axis acceleration, () → tuple(float, float, float) g
    mx, my, mz = compass.read_magnetic()               # Read 3-axis magnetic field, () → tuple(float, float, float) µT
    print('a=({:+.3f}, {:+.3f}, {:+.3f}) g  m=({:+.1f}, {:+.1f}, {:+.1f}) uT'.format(
        x, y, z, mx, my, mz))
