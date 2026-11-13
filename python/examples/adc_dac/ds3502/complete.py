"""Complete example for the DS3502 — exercise every method in the public API.

Constructs the Full driver and sets the wiper by code, voltage and ratio,
reads it back, steps with increment, inspects the MODE bit, stores the
power-up position in the EEPROM and changes the endpoint voltages.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ds3502 import DS3502Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
pot = DS3502Full(connection, v_high=15.0, v_low=0.0)     # Create DS3502 Full driver, (connection, v_high=1.0 V, v_low=0.0 V)
                                                         # writes CR = 0x80 (MODE = 1, SRAM-only) and caches WR

pot.set_wiper(100)                                       # Set wiper position, (code 0–127) → None
                                                         # writes WR only; the EEPROM is not touched
print(pot.get_wiper())                                   # Read wiper position, () → int 0–127
                                                         # reads WR; always the live wiper, never the IVR
pot.set_voltage(6.0)                                     # Set RW output voltage, (volts V) → None
                                                         # nearest code of V = V_RL + (V_RH − V_RL)·code/127
print(pot.get_voltage())                                 # Read unloaded RW voltage, () → float V
                                                         # evaluates the divider formula for the current code

pot.set_ratio(0.25)                                      # Set divider ratio, (ratio 0.0–1.0) → None
                                                         # code = round(ratio × 127)
print(pot.get_ratio())                                   # Read divider ratio, () → float
                                                         # code / 127
print(pot.get_resistance('L'))                           # Read wiper-to-RL resistance, (terminal='L') → float Ω
                                                         # nominal, RTOTAL = 10 kΩ ±20 %
print(pot.get_resistance('H'))                           # Read wiper-to-RH resistance, (terminal='H') → float Ω
print(pot.increment(5))                                  # Step the wiper, (steps=1) → int 0–127
                                                         # read, add, clamp, write; returns the new code

print(pot.get_mode())                                    # Read MODE, () → bool
                                                         # True if writes to 0x00 also program the EEPROM
pot.set_mode(False)                                      # Select SRAM-only writes, (eeprom bool) → None
                                                         # CR = 0x80; True would write CR = 0x00

pot.store_wiper(64)                                      # Store power-up position, (code=None) → None
                                                         # MODE = 0, write WR+IVR, wait for the EEPROM, MODE = 1
pot.wait_ready()                                         # Acknowledge polling, (timeout_ms=30) → None
                                                         # raises TimeoutError if the chip keeps NACKing

pot.set_endpoints(12.0, 2.0)                             # Change RH / RL voltages, (v_high V, v_low=0.0 V) → None
                                                         # affects the conversions only; no I²C traffic
