"""Minimal example for the TMP117 — print the temperature.

Constructs the driver with the defaults (identity check only, no register
writes) and reads the temperature once per second.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.temperature.tmp117 import TMP117Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
sensor = TMP117Minimal(connection)                       # Create TMP117 driver, (connection)

while True:
    t = sensor.read_temperature()                        # Read temperature, () → float °C
    print('{:.4f} °C'.format(t))
    time.sleep(1)
