"""Minimal example for the LSM303 — print acceleration and magnetic field.

The LSM303 is two I²C slaves on one bus: the accelerometer at 0x19 and the
magnetometer at 0x1E. Both connections are handed to the driver, which
initialises the chip with ±2 g / 100 Hz on the accelerometer and ±130 µT /
15 Hz on the magnetometer.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import (
    LSM303Minimal, I2C_ADDRESS_ACCEL, I2C_ADDRESS_MAG)

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
accel_connection = I2CConnection(i2c, I2C_ADDRESS_ACCEL)
mag_connection = I2CConnection(i2c, I2C_ADDRESS_MAG)
compass = LSM303Minimal(accel_connection, mag_connection)  # Create LSM303 driver, (accel_connection, mag_connection)

while True:
    ax, ay, az = compass.read_acceleration()              # Read acceleration, () → (x, y, z) float g
    mx, my, mz = compass.read_magnetic()                  # Read magnetic field, () → (x, y, z) float µT
    print('accel {:.4f} {:.4f} {:.4f} g'.format(ax, ay, az))
    print('mag {:.2f} {:.2f} {:.2f} uT'.format(mx, my, mz))
    time.sleep(1)
