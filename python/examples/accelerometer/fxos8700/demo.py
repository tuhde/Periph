"""Demo for the FXOS8700 — a tilt-compensated electronic compass with motion
wake-up.

The sensor runs in hybrid mode (100 Hz per sensor, ±4 g). After a self-test
the user rotates the board for 15 s while the hardware hard-iron calibration
tracks the extremes; then the tilt-compensated heading is printed every
250 ms. Orientation changes and motion are reported from the interrupt flags
and the chip falls asleep after 10 s of inactivity.
"""

import math
import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.fxos8700 import FXOS8700Full, FXOS8700Source, I2C_ADDRESS

DIRECTIONS = ('N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE',
              'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW')
ORIENTATIONS = ('PORTRAIT UP', 'PORTRAIT DOWN', 'LANDSCAPE RIGHT', 'LANDSCAPE LEFT')
DECLINATION = 3.0     # magnetic declination of the site in degrees
RUN_SECONDS = 60

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, I2C_ADDRESS)
compass = FXOS8700Full(connection)                        # Create FXOS8700 Full driver, (connection)

# --- Configure hybrid mode ---
# ±4 g keeps tilt maths precise while tolerating handling; magnetometer
# oversampling ratio 7 gives the lowest noise at this data rate.
compass.set_range(4)                                      # Set accelerometer range, (range_g 2|4|8) → None
compass.set_mag_oversampling(7)                           # Set magnetometer oversampling, (ratio 0..7, sleep_ratio=None) → None

# --- Check the accelerometer ---
# The electrostatic self-test must move every axis by the datasheet minimum.
ok, dx, dy, dz = compass.self_test()                      # Run accelerometer self-test, () → (ok bool, dx, dy, dz int counts)
print('self-test', 'OK' if ok else 'FAILED', dx, dy, dz)

# --- Calibrate the hard-iron offsets ---
# Auto-calibration writes the midpoint of the tracked min/max to the offset
# registers every cycle; rotate the board through all orientations meanwhile.
compass.set_mag_auto_calibration(True)                    # Hardware hard-iron calibration, (enabled bool) → None
print('Rotate the board in all directions for 15 s ...')
for second in range(15):
    time.sleep(1)
    mn, mx = compass.read_mag_min_max()                   # Read tracked min / max, () → ((x, y, z), (x, y, z)) float µT
    print('span {:.1f} {:.1f} {:.1f} uT'.format(
        mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2]))
compass.set_mag_auto_calibration(False)                   # Hardware hard-iron calibration, (enabled bool) → None
print('offsets', compass.get_mag_offset())                # Get hard-iron offsets, () → (x, y, z) float µT

# --- Arm orientation and motion detection ---
# Orientation changes and motion are routed to INT1; Auto-SLEEP drops the
# chip to a low rate after 10 s without motion, and motion wakes it again.
compass.set_orientation(debounce_ms=100)                  # Configure orientation detection, (enabled=True, debounce_ms=0, threshold_deg=45, hysteresis=4, z_lock=4, back_front=2) → None
compass.set_freefall_motion(True, threshold_g=0.3, debounce_ms=60, latch=True)  # Configure freefall / motion, (motion bool, axes=7, threshold_g=0.3, debounce_ms=0, latch=False, debounce_clear=False) → None
compass.set_auto_sleep(True, sleep_rate_hz=12.5, timeout_s=10,
                       wake_sources=FXOS8700Source.SOURCE_FF_MT)  # Configure Auto-SLEEP, (enabled bool, sleep_rate_hz=50, timeout_s=10, wake_sources=0) → None
compass.enable_interrupt(FXOS8700Source.SOURCE_ORIENTATION | FXOS8700Source.SOURCE_FF_MT, pin=1)  # Enable and route interrupt, (source mask, pin 1|2) → None

# --- Print the heading ---
# Every 250 ms the tilt-compensated heading is shown with the board's pitch
# and roll; the temperature is printed once per second.
start = time.time()
last_temperature = 0
while time.time() - start < RUN_SECONDS:
    heading = compass.heading(declination_deg=DECLINATION)  # Compute tilt-compensated heading, (declination_deg=0.0) → float degrees
    (ax, ay, az), _ = compass.read_hybrid()               # Read both sensors, () → ((x, y, z) g, (x, y, z) µT)
    roll = math.degrees(math.atan2(ay, az))
    pitch = math.degrees(math.atan2(-ax, math.sqrt(ay * ay + az * az)))
    print('{:5.1f} deg {:3s} pitch {:5.1f} roll {:5.1f}'.format(
        heading, DIRECTIONS[int((heading + 11.25) / 22.5) % 16], pitch, roll))
    status = compass.poll_interrupt()                     # Poll and clear interrupt sources, () → int
    if status & FXOS8700Source.SOURCE_ORIENTATION:
        changed, lockout, pl, back = compass.read_orientation()  # Read orientation, () → (changed, lockout, portrait_landscape, back)
        print(ORIENTATIONS[pl])
    if status & FXOS8700Source.SOURCE_FF_MT:
        print('MOTION', compass.read_system_mode())       # Read SYSMOD, () → int 0 standby | 1 wake | 2 sleep
    if time.time() - last_temperature >= 1:
        last_temperature = time.time()
        print('temperature {:.1f} C'.format(compass.read_temperature()))  # Read die temperature, () → float °C
    time.sleep(0.25)
