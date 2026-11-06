"""Minimal example for the DRV2605 — play a few built-in haptic effects.

Constructs the driver for an ERM (library A, open loop) and plays a strong
click, a double click and a buzz, waiting for each to finish.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.motor.drv2605 import DRV2605Minimal, I2C_ADDRESS, EFFECT_STRONG_CLICK, EFFECT_DOUBLE_CLICK, EFFECT_BUZZ

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
haptic = DRV2605Minimal(connection, lra=False)           # Create DRV2605 driver, (connection, lra=False)

for effect in (EFFECT_STRONG_CLICK, EFFECT_DOUBLE_CLICK, EFFECT_BUZZ):
    haptic.play_effect(effect)                           # Play a ROM effect, (effect_id 1–123) → None
    while haptic.is_playing():                           # Check playback, () → bool
        time.sleep_ms(10)
    time.sleep(0.5)

haptic.stop()                                            # Cancel playback, () → None
