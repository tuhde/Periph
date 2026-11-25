"""Complete example for the DS1881 — exercise every method in the public API.

Constructs the Full driver and sets attenuation, positions and stereo pairs,
switches between Option 1 and 2, toggles zero-crossing and the V/NV bit,
reads back the configuration and uses the table helpers. The EEPROM store is
shown last (it uses one of the 50 000 write cycles).
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ds1881 import DS1881Full, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
amp = DS1881Full(connection)                             # Create DS1881 Full driver, (connection)
amp.init()                                               # Apply defaults, () → None
                                                         # waits 1 ms, rewrites the config only if it differs

amp.set_attenuation(0, 12.0)                             # Set attenuation, (channel 0–1, db dB) → None
                                                         # nearest position, one command byte
print(amp.get_attenuation(0))                            # Read attenuation, (channel 0–1) → float dB
                                                         # three-byte round-robin read, position in dB
amp.set_position(1, 30)                                  # Set raw wiper position, (channel 0–1, position 0–63) → None
                                                         # position 63 is mute in Option 1
print(amp.get_position(1))                               # Read wiper position, (channel 0–1) → int
                                                         # low six bits of the read-back byte
amp.mute(1)                                              # Mute channel, (channel 0–1) → None
                                                         # writes the mute position (63)

amp.set_both(5, 25)                                      # Set both wipers, (position0 0–63, position1 0–63) → None
                                                         # one transaction, both wipers update together
amp.set_attenuation_both(20.0)                           # Set both attenuations, (db0 dB, db1=None dB) → None
                                                         # db1 defaults to db0 (stereo volume)
print(amp.read_all())                                    # Read everything, () → tuple (position0, position1, config)
                                                         # one three-byte round-robin read

amp.configure(2, True, False)                            # Configure, (option=1 1–2, zero_crossing=True, nonvolatile=False) → None
                                                         # writes the config byte only if it changed
print(amp.get_config())                                  # Read configuration, () → dict {option, zero_crossing, nonvolatile, raw}
                                                         # fresh read; refreshes the driver's table
print(amp.position_to_db(13), amp.max_position())        # Table lookup, (position) → float dB; Last non-mute position, () → int
                                                         # Option 2: 13 → 14 dB, max position 32
print(amp.db_to_position(39.0))                          # Nearest position, (db dB) → int
                                                         # ≥ 70 dB maps to mute
amp.set_option(1)                                        # Select option, (option 1–2) → None
                                                         # positions are not converted
amp.set_zero_crossing(False)                             # Set zero-crossing, (enabled bool) → None
                                                         # ZC bit of the configuration
amp.set_zero_crossing(True)                              # Set zero-crossing, (enabled bool) → None
                                                         # clickless updates again
amp.wait_ready(0.05)                                     # Acknowledge-poll, (timeout_s=0.05 s) → None
                                                         # returns once the chip ACKs its address

amp.set_nonvolatile(True)                                # Select EEPROM storage, (enabled bool) → None
                                                         # V/NV bit; every wiper write now wears the EEPROM (50 000 writes)
amp.store()                                              # Store positions, () → None
                                                         # rewrites both wipers and polls for completion
amp.set_nonvolatile(False)                               # Select EEPROM storage, (enabled bool) → None
                                                         # back to volatile wipers
time.sleep(1)
amp.reset()                                              # Reset, () → None
                                                         # volatile, zero-crossing, Option 1, both wipers muted
