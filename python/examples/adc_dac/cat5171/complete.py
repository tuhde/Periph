"""Complete example for the CAT5171 — exercise every method in the public API.

Constructs the Full driver and sets the wiper by code, ratio and resistance,
reads it back, steps with increment, exercises midscale, shutdown/resume and
reset, and changes the resistance model.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.cat5171 import CAT5171Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
pot = CAT5171Full(connection)                            # Create CAT5171 Full driver, (connection, r_ab=100000.0 Ω, r_wiper=50.0 Ω)

pot.set_wiper(100)                                       # Set wiper position, (code 0–255) → None
                                                         # writes [instruction, code] with RS = 0, SD = 0
print(pot.get_wiper())                                   # Read wiper position, () → int 0–255
                                                         # bare one-byte read; no instruction byte is sent
pot.set_resistance(50000.0)                              # Set W–B resistance, (ohms Ω) → None
                                                         # inverts R = D/256·RAB + RW to the nearest code
print(pot.get_resistance())                              # Read W–B resistance, () → float Ω
                                                         # evaluates D/256·RAB + RW for the current code

pot.set_ratio(0.25)                                      # Set divider ratio, (ratio 0.0–1.0) → None
                                                         # code = round(ratio × 256), clamped to 255
print(pot.get_ratio())                                   # Read divider ratio, () → float
                                                         # code / 256
pot.set_resistance_wa(30000.0)                           # Set W–A resistance, (ohms Ω) → None
                                                         # rheostat between W and A with B open
print(pot.get_resistance_wa())                           # Read W–A resistance, () → float Ω
                                                         # (256 − D)/256·RAB + RW
print(pot.increment(5))                                  # Step the wiper, (steps=1) → int 0–255
                                                         # read, add, clamp, write; returns the new code

pot.midscale()                                           # Move wiper to midscale, () → None
                                                         # RS = 1 instruction; wiper goes to 0x80
pot.shutdown()                                           # Shut down, () → None
                                                         # SD = 1: A opens, wiper shorts to B, WCR kept
print(pot.is_shutdown())                                 # Query shutdown state, () → bool
                                                         # driver-tracked; the chip cannot report it
time.sleep(1)
pot.resume()                                             # Leave shutdown, () → None
                                                         # rewrites the kept code with SD = 0

pot.set_rab(50000.0, 120.0)                              # Change resistance model, (r_ab Ω, r_wiper=None Ω) → None
                                                         # affects conversions only; no I²C traffic
pot.reset()                                              # Midscale reset, () → None
                                                         # RS = 1, SD = 0; wiper returns to 0x80
