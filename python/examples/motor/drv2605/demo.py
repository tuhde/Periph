"""Demo for the DRV2605 — haptic notification showcase.

Checks the actuator with diagnostics, calibrates it, plays a strong, double
and triple click, a "heartbeat" sequence, then a 2-second amplitude ramp
while printing the supply voltage (and LRA frequency). Finishes by reading
the status flags.
"""

import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.motor.drv2605 import (DRV2605Full, I2C_ADDRESS, EFFECT_STRONG_CLICK,
                                        EFFECT_DOUBLE_CLICK, EFFECT_TRIPLE_CLICK)

ACTUATOR = 'ERM'   # set to 'LRA' for a linear resonant actuator
LRA = ACTUATOR == 'LRA'

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
haptic = DRV2605Full(connection, lra=LRA)                # Create DRV2605 Full driver, (connection, lra=False)

# --- Verify and calibrate the actuator ---
# diagnose() reports a missing or shorted actuator; calibrate() measures the
# back-EMF so the closed-loop engine matches this particular motor.
print('actuator ok:', haptic.diagnose())                 # Run actuator diagnostics, () → bool
ok, comp, bemf, gain = haptic.calibrate(LRA, 3.0, 3.0, 175.0 if LRA else None)  # Run auto-calibration, (lra, rated_voltage V, overdrive_voltage V, lra_freq_hz Hz) → (bool, int, int, int)
print('calibration:', ok, comp, bemf, gain)

# --- Play the three click effects ---
for effect in (EFFECT_STRONG_CLICK, EFFECT_DOUBLE_CLICK, EFFECT_TRIPLE_CLICK):
    haptic.play_effect(effect)                           # Play a ROM effect, (effect_id 1–123) → None
    haptic.wait_done()                                   # Wait for playback, (timeout_ms=5000) → bool
    time.sleep(0.5)

# --- Heartbeat: two strong clicks 100 ms apart ---
# The sequencer plays both effects and the delay without host involvement.
haptic.play_sequence([EFFECT_STRONG_CLICK, ("wait", 100), EFFECT_STRONG_CLICK])  # Play a sequence, (items ≤ 8) → None
haptic.wait_done()                                       # Wait for playback, (timeout_ms=5000) → bool
time.sleep(0.5)

# --- Two-second amplitude ramp ---
# Real-time playback streams the amplitude; VBAT and LRA_PERIOD are valid
# while the actuator is driven.
for step in range(21):
    haptic.rtp_level(step / 20)                          # Stream an RTP amplitude, (level 0.0–1.0) → None
    time.sleep_ms(100)
    print('vdd %.2f V' % haptic.read_supply_voltage())   # Read supply voltage, () → float V
    if LRA:
        print('lra', haptic.read_lra_frequency())        # Read LRA frequency, () → float Hz | None
haptic.stop()                                            # Cancel playback, () → None

# --- Report latched faults ---
print('status (diag, fb, otemp, ocp):', haptic.read_status())  # Read status flags, () → (bool, bool, bool, bool)
