"""Complete example for the AD5243 — exercise every method in the public API.

Constructs the Full driver and sets wipers by code, ratio and resistance,
reads them back, steps with increment, exercises shutdown/resume and reset,
and changes the resistance model.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ad5243 import AD5243Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
pot = AD5243Full(connection)                             # Create AD5243 Full driver, (connection, r_ab=10000.0 Ω, r_wiper=160.0 Ω)

pot.set_wiper(0, 100)                                    # Set wiper position, (channel 0–1, code 0–255) → None
                                                         # writes [instruction, code]; the instruction byte selects the channel
print(pot.get_wiper(0))                                  # Read wiper position, (channel 0–1) → int 0–255
                                                         # sends the instruction alone, then reads one byte
pot.set_resistance(0, 5000.0)                            # Set W–B resistance, (channel 0–1, ohms Ω) → None
                                                         # inverts R = D/256·RAB + 2·RW to the nearest code
print(pot.get_resistance(0))                             # Read W–B resistance, (channel 0–1) → float Ω
                                                         # evaluates D/256·RAB + 2·RW for the current code

pot.set_both(32, 224)                                    # Write both wipers, (code1 0–255, code2 0–255) → None
                                                         # two transactions, channel 0 then channel 1
pot.set_ratio(0, 0.25)                                   # Set divider ratio, (channel 0–1, ratio 0.0–1.0) → None
                                                         # code = round(ratio × 256), clamped to 255
print(pot.get_ratio(0))                                  # Read divider ratio, (channel 0–1) → float
                                                         # code / 256
pot.set_resistance_wa(1, 3000.0)                         # Set W–A resistance, (channel 0–1, ohms Ω) → None
                                                         # rheostat between W and A with B open
print(pot.get_resistance_wa(1))                          # Read W–A resistance, (channel 0–1) → float Ω
                                                         # (256 − D)/256·RAB + 2·RW
print(pot.increment(0, 5))                               # Step the wiper, (channel 0–1, steps=1) → int 0–255
                                                         # read, add, clamp, write; returns the new code

pot.shutdown(1)                                          # Shut channel down, (channel 0–1) → None
                                                         # SD = 1: A opens, wiper shorts to B, RDAC kept
print(pot.is_shutdown(1))                                # Query shutdown state, (channel 0–1) → bool
                                                         # driver-tracked; the chip cannot report it
time.sleep(1)
pot.resume(1)                                            # Leave shutdown, (channel 0–1) → None
                                                         # rewrites the kept code with SD = 0

pot.set_rab(50000.0, 120.0)                              # Change resistance model, (r_ab Ω, r_wiper=None Ω) → None
                                                         # affects conversions only; no I²C traffic
pot.reset()                                              # Restore power-up preset, () → None
                                                         # writes 0x80 to both channels with SD = 0
