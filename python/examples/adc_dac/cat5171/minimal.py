"""Minimal example for the CAT5171 — set and read back a digital potentiometer.

Constructs the driver with the defaults (100 kΩ part) and steps the wiper
through a few positions, printing the resulting W–B resistance.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.cat5171 import CAT5171Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
pot = CAT5171Minimal(connection)                         # Create CAT5171 driver, (connection, r_ab=100000.0 Ω, r_wiper=50.0 Ω)

for code in (0, 64, 128, 192, 255):
    pot.set_wiper(code)                                  # Set wiper position, (code 0–255) → None
    print(code, pot.get_wiper())                         # Read wiper position, () → int 0–255
    print(pot.get_resistance())                          # Read W–B resistance, () → float Ω
    time.sleep(0.5)

pot.set_resistance(22000.0)                              # Set W–B resistance, (ohms Ω) → None
