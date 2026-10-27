"""Minimal example for the LSM303 — read acceleration and magnetic field.

Constructs the driver with the defaults (±2 g / 100 Hz / high-resolution
accelerometer, ±130 µT / 15 Hz magnetometer) and prints each sample.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Minimal

# Both slaves share one bus; adjust the pins for your wiring.
i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
accel_connection = I2CConnection(i2c, 0x19)                 # Create accelerometer connection, (i2c, addr=0x19)
mag_connection = I2CConnection(i2c, 0x1E)                   # Create magnetometer connection, (i2c, addr=0x1E)
compass = LSM303Minimal(accel_connection, mag_connection)   # Create LSM303 driver, (accel_connection, mag_connection)

for _ in range(10):
    ax, ay, az = compass.read_acceleration()                # Read 3-axis acceleration, () → tuple(float, float, float) g
    mx, my, mz = compass.read_magnetic()                    # Read 3-axis magnetic field, () → tuple(float, float, float) µT
    print('a=({:.3f}, {:.3f}, {:.3f}) g  m=({:.1f}, {:.1f}, {:.1f}) µT'.format(
        ax, ay, az, mx, my, mz))
