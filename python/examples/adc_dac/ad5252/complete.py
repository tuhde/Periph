"""Complete example for the AD5252 — exercise every method in the public API.

Constructs the Full driver and sets wipers by code, ratio and resistance,
reads them back, uses the quick commands, stores and restores the wipers,
accesses the user EEMEM and the stored RAB tolerance, and changes the
resistance model.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ad5252 import AD5252Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
pot = AD5252Full(connection)                             # Create AD5252 Full driver, (connection, r_ab=10000.0 Ω, r_wiper=75.0 Ω)

pot.set_wiper(0, 100)                                    # Set wiper position, (channel 0–1, code 0–255) → None
                                                         # writes [instruction, code]; the instruction byte selects the channel
print(pot.get_wiper(0))                                  # Read wiper position, (channel 0–1) → int 0–255
                                                         # pointer write of the instruction, then a one-byte read
pot.set_resistance(0, 5000.0)                            # Set W–B resistance, (channel 0–1, ohms Ω) → None
                                                         # inverts R = D/256·RAB + RW to the nearest code
print(pot.get_resistance(0))                             # Read W–B resistance, (channel 0–1) → float Ω
                                                         # evaluates D/256·RAB + RW for the current code

pot.set_both(32, 224)                                    # Write both wipers, (code0 0–255, code1 0–255) → None
                                                         # one consecutive-write transaction (RDAC1 then RDAC3)
pot.set_ratio(0, 0.25)                                   # Set divider ratio, (channel 0–1, ratio 0.0–1.0) → None
                                                         # code = round(ratio × 256), clamped to 255
print(pot.get_ratio(0))                                  # Read divider ratio, (channel 0–1) → float
                                                         # code / 256
pot.set_resistance_wa(1, 3000.0)                         # Set W–A resistance, (channel 0–1, ohms Ω) → None
                                                         # rheostat between W and A with B open
print(pot.get_resistance_wa(1))                          # Read W–A resistance, (channel 0–1) → float Ω
                                                         # (256 − D)/256·RAB + RW

pot.increment(0)                                         # Step up one code, (channel=None 0–1) → None
                                                         # quick command; None addresses both channels
pot.decrement(0)                                         # Step down one code, (channel=None 0–1) → None
                                                         # quick command; saturates at 0
pot.double(0)                                            # Raise by 6 dB, (channel=None 0–1) → None
                                                         # quick command; the code doubles, saturating at 255
pot.halve(0)                                             # Lower by 6 dB, (channel=None 0–1) → None
                                                         # quick command; the code is halved

pot.store()                                              # Store wipers in EEMEM, (channel=None 0–1) → None
                                                         # quick command, then ACK polling (≈ 26 ms)
pot.set_both(1, 2)
pot.restore()                                            # Restore wipers from EEMEM, (channel=None 0–1) → None
                                                         # quick command followed by NOP
print(pot.get_wiper(0), pot.get_wiper(1))

pot.write_eemem(4, 0x5A)                                 # Write user EEMEM byte, (index 4–15, value 0–255) → None
                                                         # followed by ACK polling (≈ 26 ms)
print(pot.read_eemem(4))                                 # Read user EEMEM byte, (index 4–15) → int 0–255
                                                         # pointer write, then a one-byte read
print(pot.read_tolerance(0))                             # Read stored RAB tolerance, (channel 0–1) → float %
                                                         # sign-magnitude integer byte + decimal byte / 256
print(pot.rab_actual(0))                                 # Actual RAB of a channel, (channel 0–1) → float Ω
                                                         # r_ab × (1 + tolerance / 100)
pot.nop()                                                # Send NOP, () → None
                                                         # quick command 0x80; the chip returns to idle

pot.set_rab(50000.0, 120.0)                              # Change resistance model, (r_ab Ω, r_wiper=None Ω) → None
                                                         # affects conversions only; no I²C traffic
time.sleep(1)
pot.reset()                                              # Reset from EEMEM, () → None
                                                         # quick command 0xB8, then ACK polling
