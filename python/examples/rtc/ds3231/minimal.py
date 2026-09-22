"""Minimal example for the DS3231 — read the calendar clock and temperature.

Constructs the driver with the defaults (24-hour mode, oscillator always
running) and prints each reading to stdout.
"""

from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.rtc.ds3231 import DS3231Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
rtc = DS3231Minimal(connection)                          # Create DS3231 driver, (connection)

for _ in range(10):
    year, month, day, weekday, hour, minute, second = rtc.get_datetime()  # Read calendar clock, () → (year, month, day, weekday, hour, minute, second)
    temp_c = rtc.read_temperature()                     # Read on-chip temperature, () → float C
    print('{:04d}-{:02d}-{:02d} {:02d}:{:02d}:{:02d}  {:.2f} C'.format(
        year, month, day, hour, minute, second, temp_c))
