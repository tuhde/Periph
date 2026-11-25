"""Minimal example for the MAX44009 — read the ambient illuminance.

Constructs the driver with its power-on defaults (automatic range, one
measurement every 800 ms) and prints the illuminance once a second.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.light.max44009 import MAX44009Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = MAX44009Minimal(connection)                     # Create MAX44009 driver, (connection)

while True:
    print('{:.2f} lx'.format(sensor.lux()))              # Read illuminance, () → float lux
    time.sleep(1)
