"""Minimal example for the ISL29125 — read red, green and blue counts.

Constructs the driver with its defaults (continuous RGB, 16 bit, 10 000 lux
range, maximum IR compensation) and prints the three channel counts once a
second.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.color.isl29125 import ISL29125Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = ISL29125Minimal(connection)                     # Create ISL29125 driver, (connection)
                                                         # checks DEVICE_ID, starts RGB conversion

while True:
    c = sensor.read()                                    # Read the three channels, () → dict
                                                         # {red, green, blue} unsigned 16-bit counts
    print('R', c['red'], 'G', c['green'], 'B', c['blue'])
    time.sleep(1)
