"""BMA180 demo example — tilt meter with tap and free-fall detection.

Tier-1 signature comments + Tier-3 narrative blocks at each section boundary.
Tier-2 per-call lines are omitted in the demo.
"""

import math
import time
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.bma180 import BMA180Full, BMA180Full as C
from machine import I2C, Pin

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400000)
connection = I2CConnection(i2c, 0x40)
chip = BMA180Full(connection)                          # Create BMA180 full driver, (connection)

# --- Configure for tilt + tap + free-fall demo at low-noise, 40 Hz, ±2 g ---
# 40 Hz bandwidth still meets the 0.3 g / 40 ms free-fall requirement and
# keeps the per-sample noise below 1 LSB on the 4096 LSB/g scale.
chip.set_bandwidth(40)                                 # Set bandwidth, (bandwidth_hz=40 Hz) → None

# --- Calibrate zero-g while the board sits level ---
# Asks the user to place the board flat before continuing.
input("Place the board flat, then press Enter to calibrate: ")
chip.calibrate_offset(0x07, 1)                        # Calibrate offset, (axes=0x07, mode=1 fine) → None

# --- Arm tap and free-fall detection with latching so we never miss an event ---
chip.set_tap(0.5, 250)                                # Configure tap, (threshold_g=0.5, window_ms=250) → None
chip.set_low_g(0.3, 40)                               # Configure low-g, (threshold_g=0.3, duration_ms=40) → None
chip.set_latch(True)                                  # Set latch, (enabled=True) → None

# --- Print tilt + temperature every 100 ms; poll interrupts for tap/free-fall ---
start = time.ticks_ms()
while time.ticks_diff(time.ticks_ms(), start) < 60_000:
    x, y, z = chip.read()                             # Read 3-axis acceleration, () → tuple g
    pitch = math.degrees(math.atan2(x, math.sqrt(y*y + z*z)))
    roll  = math.degrees(math.atan2(y, math.sqrt(x*x + z*z)))
    mag   = math.sqrt(x*x + y*y + z*z)
    t     = chip.read_temperature()                   # Read temperature, () → float °C
    print("pitch={:+.1f} roll={:+.1f} |a|={:.3f} g  T={:+.1f} C".format(pitch, roll, mag, t))

    flags = chip.poll_interrupt()                     # Poll interrupt, () → int
    if flags & C._STATUS_TAP:
        print("DOUBLE TAP")
        chip.clear_interrupt()                        # Clear interrupt, () → None
    if flags & C._STATUS_LOW_G:
        print("FREE FALL")
        chip.clear_interrupt()                        # Clear interrupt, () → None

    time.sleep_ms(100)