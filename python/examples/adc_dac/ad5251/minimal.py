"""Minimal example for the AD5251 — set and read back a digital potentiometer.

Constructs the driver with the defaults (50 kΩ part) and steps channel 0
through a few wiper positions, printing the resulting W–B resistance.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ad5251 import AD5251Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
pot = AD5251Minimal(connection)                          # Create AD5251 driver, (connection, r_ab=50000.0 Ω, r_wiper=75.0 Ω)

for code in (0, 16, 32, 48, 63):
    pot.set_wiper(0, code)                               # Set wiper position, (channel 0–1, code 0–63) → None
    print(code, pot.get_wiper(0))                        # Read wiper position, (channel 0–1) → int 0–63
    print(pot.get_resistance(0))                         # Read W–B resistance, (channel 0–1) → float Ω
    time.sleep(0.5)

pot.set_resistance(1, 20000.0)                            # Set W–B resistance, (channel 0–1, ohms Ω) → None
