"""Minimal example for the AD5243 — set and read back a digital potentiometer.

Constructs the driver with the defaults (10 kΩ part) and steps channel 0
through a few wiper positions, printing the resulting W–B resistance.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ad5243 import AD5243Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
pot = AD5243Minimal(connection)                          # Create AD5243 driver, (connection, r_ab=10000.0 Ω, r_wiper=160.0 Ω)

for code in (0, 64, 128, 192, 255):
    pot.set_wiper(0, code)                               # Set wiper position, (channel 0–1, code 0–255) → None
    print(code, pot.get_wiper(0))                        # Read wiper position, (channel 0–1) → int 0–255
    print(pot.get_resistance(0))                         # Read W–B resistance, (channel 0–1) → float Ω
    time.sleep(0.5)

pot.set_resistance(1, 2200.0)                            # Set W–B resistance, (channel 0–1, ohms Ω) → None
