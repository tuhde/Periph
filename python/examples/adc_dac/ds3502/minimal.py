"""Minimal example for the DS3502 — set and read back a high-voltage digital potentiometer.

Constructs the driver with the defaults (RH = 1.0 V, RL = 0 V, so voltages are
ratios) and steps the wiper through a few positions, printing the unloaded
output voltage.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ds3502 import DS3502Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
pot = DS3502Minimal(connection, v_high=15.0, v_low=0.0)  # Create DS3502 driver, (connection, v_high=1.0 V, v_low=0.0 V)

for code in (0, 32, 64, 96, 127):
    pot.set_wiper(code)                                  # Set wiper position, (code 0–127) → None
    print(code, pot.get_wiper())                         # Read wiper position, () → int 0–127
    print(pot.get_voltage())                             # Read unloaded RW voltage, () → float V
    time.sleep(0.5)

pot.set_voltage(6.0)                                     # Set RW output voltage, (volts V) → None
