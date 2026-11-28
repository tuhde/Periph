"""Minimal example for the TSL2561 — read the illuminance.

Constructs the driver with its defaults (1× gain, 402 ms integration, T/FN/CL
package coefficients) and prints the illuminance once a second.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.light.tsl2561 import TSL2561Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = TSL2561Minimal(connection)                      # Create TSL2561 driver, (connection)

while True:
    print('{:.1f} lx'.format(sensor.read_lux()))         # Read illuminance, () → float lux
    time.sleep(1)
