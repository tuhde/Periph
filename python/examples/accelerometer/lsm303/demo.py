"""Demo for the LSM303 — tilt-compensated electronic compass with gestures.

Configures ±2 *g* / 50 Hz / high-resolution and ±130 µT / 15 Hz, arms a
Z-axis double tap (0.8 g) and a latched free-fall detector (0.35 g / 60 ms)
both routed to INT1, and runs the FIFO in stream mode with a 16-sample
watermark. Every second it drains the FIFO, averages the samples, reads the
magnetic field and prints the tilt-compensated heading with a 16-point
compass name, pitch / roll, field magnitude and relative temperature.
Prints DOUBLE TAP or FREE FALL whenever :meth:`poll_interrupt` reports the
respective source. Runs for 60 s then exits.
"""

import math
import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Full, LSM303Source

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
accel_connection = I2CConnection(i2c, 0x19)
mag_connection = I2CConnection(i2c, 0x1E)
compass = LSM303Full(accel_connection, mag_connection)      # Create LSM303 Full driver, (accel_connection, mag_connection)

NAMES = ('N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE',
         'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW')

# --- Configure ±2 g / 50 Hz, ±130 µT / 15 Hz ---
compass.set_range(2)                                        # Set accelerometer range, (range_g=2) → None g
compass.set_mode(2)                                         # Set accelerometer mode, (mode=2) → None
compass.set_data_rate(50)                                   # Set accelerometer data rate, (rate_hz=100) → None Hz
compass.set_mag_range(130)                                  # Set magnetometer range, (range_ut=130) → None µT
compass.set_mag_data_rate(15)                               # Set magnetometer data rate, (rate_hz=15) → None Hz

# --- Arm the double tap and free-fall detectors on INT1 ---
# Free-fall is latched so a transient cannot slip past the 1 s loop;
# the click window (200 ms) covers a slow double tap.
compass.set_click(single=0, double=0x04, threshold_g=0.8,
                  time_limit_ms=20, latency_ms=40,
                  window_ms=200)                            # Configure click detection, (single=0, double=0, threshold_g=0.5, time_limit_ms=10, latency_ms=20, window_ms=50) → None
compass.set_free_fall(generator=1, threshold_g=0.35,
                      duration_ms=60)                       # Configure free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → None
compass.set_latch(1, True)                                  # Latch generator 1, (generator=1, enabled=True) → None
compass.enable_interrupt(LSM303Source.SOURCE_CLICK, 1)      # Route source to INT1, (source, pin=1) → None
compass.enable_interrupt(LSM303Source.SOURCE_IA1, 1)        # Route source to INT1, (source, pin=1) → None

# --- Stream the FIFO with a 16-sample watermark ---
compass.set_fifo(2, watermark=16)                           # Configure FIFO, (mode=0, watermark=0, trigger_int2=False) → None

start = time.ticks_ms()
last_sample = start

# --- 60-second compass / gesture monitor ---
while time.ticks_diff(time.ticks_ms(), start) < 60_000:
    now = time.ticks_ms()

    if time.ticks_diff(now, last_sample) >= 1000:
        # Average the accelerometer samples drained from the FIFO.
        samples = compass.read_fifo()                       # Drain FIFO, (count=None) → list(tuple(float, float, float)) g
        level = compass.fifo_level()                        # Read FIFO level, () → int
        if samples:
            n = len(samples)
            ax = sum(s[0] for s in samples) / n
            ay = sum(s[1] for s in samples) / n
            az = sum(s[2] for s in samples) / n
        else:
            ax, ay, az = compass.read_acceleration()        # Read 3-axis acceleration, () → tuple(float, float, float) g

        mx, my, mz = compass.read_magnetic()                # Read 3-axis magnetic field, () → tuple(float, float, float) µT
        field = math.sqrt(mx * mx + my * my + mz * mz)
        pitch = math.degrees(math.atan2(ax, math.sqrt(ay * ay + az * az)))
        roll = math.degrees(math.atan2(ay, az))
        hdg = compass.heading(tilt_compensate=True)         # Compute compass heading, (tilt_compensate=True) → float °
        name = NAMES[int((hdg + 11.25) / 22.5) % 16]
        temp = compass.read_temperature_delta()             # Read relative temperature, () → float °C
        print('{:5.1f}° {}  pitch={:+6.1f} roll={:+6.1f} |B|={:.1f} µT  T={:+.1f} °C  fifo={}'.format(
            hdg, name, pitch, roll, field, temp, level))

        status = compass.poll_interrupt()                   # Poll interrupt status, () → int
        if status & LSM303Source.SOURCE_CLICK:
            print('DOUBLE TAP')
        if status & LSM303Source.SOURCE_IA1:
            print('FREE FALL')
        last_sample = now

    time.sleep_ms(10)

print('done')
