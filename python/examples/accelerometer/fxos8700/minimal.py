"""Minimal example for the FXOS8700 — print acceleration and magnetic field.

The FXOS8700 is a 6-axis e-compass behind one I²C address. The driver starts
hybrid mode at 100 Hz per sensor with ±2 g and a magnetometer oversampling
ratio of 7; nothing has to be configured.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.fxos8700 import FXOS8700Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
compass = FXOS8700Minimal(connection)                     # Create FXOS8700 driver, (connection)

while True:
    ax, ay, az = compass.read_accel()                     # Read acceleration, () → (x, y, z) float g
    mx, my, mz = compass.read_mag()                       # Read magnetic field, () → (x, y, z) float µT
    print('accel {:.4f} {:.4f} {:.4f} g'.format(ax, ay, az))
    print('mag {:.2f} {:.2f} {:.2f} uT'.format(mx, my, mz))
    time.sleep(1)
