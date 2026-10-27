"""Minimal example for the LSM303DLHC — read acceleration and magnetic field in a loop.

Constructs the driver with the defaults (±2 g accelerometer at 100 Hz in
high-resolution mode, ±130 µT magnetometer at 15 Hz) and prints each sample
to stdout. Both sensors share one I²C bus at their fixed addresses (0x19 and 0x1E).
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Minimal

# I²C bus on most ESP32 / Pi Pico dev boards; adjust for your wiring.
i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
accel_conn = I2CConnection(i2c, 0x19)                   # Create accelerometer connection, (bus, addr=0x19) → None
mag_conn = I2CConnection(i2c, 0x1E)                     # Create magnetometer connection, (bus, addr=0x1E) → None
compass = LSM303Minimal(accel_conn, mag_conn)           # Create LSM303 driver, (accel_connection, mag_connection) → None

for _ in range(10):
    ax, ay, az = compass.read_acceleration()            # Read 3-axis acceleration, () → tuple(float, float, float) g
    mx, my, mz = compass.read_magnetic()                # Read 3-axis magnetic field, () → tuple(float, float, float) µT
    print('a=({:+.3f}, {:+.3f}, {:+.3f}) g  m=({:+.1f}, {:+.1f}, {:+.1f}) µT'.format(ax, ay, az, mx, my, mz))
    time.sleep(0.1)
