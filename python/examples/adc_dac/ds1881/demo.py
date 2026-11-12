"""Demo for the DS1881 — stereo volume control with mute and optional EEPROM store.

Ramps both channels from 0 dB to −40 dB in 2 dB steps and back, mutes and
unmutes channel 0 only, prints the Option 2 table points via position_to_db
and shows the configuration. With ``--store`` the positions are saved to
EEPROM (rated for only 50 000 writes).
"""

import sys
import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ds1881 import DS1881Full, I2C_ADDRESS

STORE = '--store' in getattr(sys, 'argv', [])

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
amp = DS1881Full(connection)                             # Create DS1881 Full driver, (connection)
amp.init()                                               # Apply defaults, () → None

# --- Fade the volume down and back up ---
# Zero-crossing detection (on by default) moves the wiper only at a signal
# zero crossing, so each 2 dB step is clickless; 100 ms per step is longer
# than the 50 ms maximum zero-crossing window.
for db in list(range(0, 42, 2)) + list(range(40, -2, -2)):
    amp.set_attenuation_both(float(db))                  # Set both attenuations, (db0 dB, db1=None dB) → None
    p0, p1, _ = amp.read_all()                           # Read everything, () → tuple (position0, position1, config)
    print('{:3d} dB -> positions {} {}'.format(db, p0, p1))
    time.sleep(0.1)

# --- Mute and unmute one channel ---
# Muting channel 0 leaves channel 1 playing; restoring writes the position again.
amp.mute(0)                                              # Mute channel, (channel 0–1) → None
print('channel 0 muted:', amp.get_attenuation(0))        # Read attenuation, (channel 0–1) → float dB
time.sleep(1)
amp.set_attenuation(0, 0.0)                              # Set attenuation, (channel 0–1, db dB) → None

# --- Inspect the Option 2 attenuation table ---
# Option 2 (DS1808 compatible) uses 1, 2 and 3 dB steps; positions keep their
# numeric value when the option changes, so re-set them afterwards.
amp.set_option(2)                                        # Select option, (option 1–2) → None
for position in (0, 12, 13, 24, 25, 32, 33):
    print('position', position, '->', amp.position_to_db(position), 'dB')  # Table lookup, (position) → float dB
amp.set_option(1)                                        # Select option, (option 1–2) → None
amp.set_attenuation_both(10.0)                           # Set both attenuations, (db0 dB, db1=None dB) → None
print(amp.get_config())                                  # Read configuration, () → dict {option, zero_crossing, nonvolatile, raw}

# --- Optionally persist the volume ---
# Stored positions are restored at power-up. The EEPROM is rated for only
# 50 000 writes, so this runs only when requested with --store.
if STORE:
    print('WARNING: EEPROM endurance is 50 000 writes')
    amp.set_nonvolatile(True)                            # Select EEPROM storage, (enabled bool) → None
    start = time.ticks_ms()
    amp.store()                                          # Store positions, () → None
    print('store took', time.ticks_diff(time.ticks_ms(), start), 'ms')
    amp.set_nonvolatile(False)                           # Select EEPROM storage, (enabled bool) → None
