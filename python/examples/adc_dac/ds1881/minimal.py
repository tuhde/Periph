"""Minimal example for the DS1881 — set the volume of two audio channels.

Constructs the driver, applies the defaults (volatile, zero-crossing, 1 dB
steps) with init() and steps both channels through a few attenuations,
printing the read-back values.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.adc_dac.ds1881 import DS1881Minimal, I2C_ADDRESS

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
amp = DS1881Minimal(connection)                          # Create DS1881 driver, (connection)
amp.init()                                               # Apply defaults, () → None

for db in (0.0, 10.0, 20.0, 40.0):
    amp.set_attenuation(0, db)                           # Set attenuation, (channel 0–1, db dB) → None
    amp.set_attenuation(1, db)                           # Set attenuation, (channel 0–1, db dB) → None
    print(db, amp.get_attenuation(0))                    # Read attenuation, (channel 0–1) → float dB
    time.sleep(0.5)

amp.mute(0)                                              # Mute channel, (channel 0–1) → None
