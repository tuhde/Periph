"""Minimal example for the APDS-9250 — read red, green, blue and IR counts.

Constructs the driver with its defaults (colour mode, 18 bit, 100 ms, gain x3)
and prints the four channel counts once a second.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.light.apds9250 import APDS9250Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = APDS9250Minimal(connection)                     # Create APDS-9250 driver, (connection)
                                                         # checks PART_ID, starts colour mode

while True:
    c = sensor.read()                                    # Read the four channels, () → dict
                                                         # {red, green, blue, ir} unsigned counts
    print('R', c['red'], 'G', c['green'], 'B', c['blue'], 'IR', c['ir'])
    time.sleep(1)
