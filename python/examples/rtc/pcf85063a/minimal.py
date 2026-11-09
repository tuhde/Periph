"""Minimal example for the PCF85063A — read the calendar clock.

Constructs the driver with the defaults (24-hour mode, clock running) and
prints the time to stdout.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.rtc.pcf85063a import PCF85063AMinimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
rtc = PCF85063AMinimal(connection)                       # Create PCF85063A driver, (connection)

for _ in range(10):
    year, month, day, weekday, hour, minute, second = rtc.get_datetime()  # Read calendar clock, () → (year, month, day, weekday, hour, minute, second)
    print('{:04d}-{:02d}-{:02d} {:02d}:{:02d}:{:02d}'.format(
        year, month, day, hour, minute, second))
    time.sleep(1)
