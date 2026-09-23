"""Minimal example for the PCF8523 — read the battery-backed calendar clock.

Constructs the driver with the defaults (24-hour mode, battery switch-over
in standard mode with battery-low detection) and prints the time to stdout.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.rtc.pcf8523 import PCF8523Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
rtc = PCF8523Minimal(connection)                         # Create PCF8523 driver, (connection)

for _ in range(10):
    year, month, day, weekday, hour, minute, second = rtc.get_datetime()  # Read calendar clock, () → (year, month, day, weekday, hour, minute, second)
    print('{:04d}-{:02d}-{:02d} {:02d}:{:02d}:{:02d}'.format(
        year, month, day, hour, minute, second))
    time.sleep(1)
