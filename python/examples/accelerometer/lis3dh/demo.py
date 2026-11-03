"""Demo for the LIS3DH — tilt, double-tap and free-fall monitor.

Configure ±2 *g*, 50 Hz, high-resolution mode, run the self-test once,
arm a Z-axis double tap (0.8 g) and a free-fall detector (0.35 g / 60 ms)
both latched and routed to INT1, and run the FIFO in stream mode with a
16-sample watermark. Drain the FIFO every 100 ms, average the samples and
print pitch / roll, the vector magnitude and the relative temperature;
print DOUBLE TAP or FREE FALL whenever :meth:`poll_interrupt` reports the
respective source. Runs for 60 s then exits.
"""

import math
import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lis3dh import LIS3DHFull, LIS3DHSource

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x18)
accel = LIS3DHFull(connection)                              # Create LIS3DH Full driver, (connection)

# --- Configure ±2 g / 50 Hz high-resolution and verify the part ---
# The self-test only passes on healthy silicon, so run it before arming
# anything; 50 Hz keeps the self-test window short (2 samples).
accel.set_data_rate(50)                                     # Set output data rate, (rate_hz=100) → None Hz
accel.set_range(2)                                          # Set full-scale range, (range_g=2) → None g
accel.set_mode(2)                                           # Set operating mode, (mode=2) → None
ok = accel.self_test(mode=1)                                # Run self-test, (mode=1) → bool
print('self-test:', 'PASS' if ok else 'FAIL')

# --- Arm the double-tap and free-fall detectors on INT1 ---
# Both sources are latched so a transient cannot slip past the 100 ms
# polling loop; the click window (200 ms) covers a slow double tap.
accel.set_click(single=0, double=0x04, threshold_g=0.8,
                time_limit_ms=20, latency_ms=40,
                window_ms=200, latch=True)                  # Configure click detection, (single=0, double=0, threshold_g=0.5, time_limit_ms=10, latency_ms=20, window_ms=50, latch=False) → None
accel.set_free_fall(generator=1, threshold_g=0.35, duration_ms=60)  # Configure free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → None
accel.set_latch(1, True)                                    # Latch generator 1, (generator=1, enabled=True) → None
accel.enable_interrupt(LIS3DHSource.SOURCE_CLICK, pin=1)    # Route source to INT pin, (source, pin=1) → None
accel.enable_interrupt(LIS3DHSource.SOURCE_IA1, pin=1)      # Route source to INT pin, (source, pin=1) → None

# --- Run the FIFO in stream mode with a 16-sample watermark ---
# Stream mode overwrites the oldest samples once full, so a slow reader
# always sees recent motion instead of going stale.
accel.set_fifo(2, watermark=16)                             # Configure FIFO, (mode=0, watermark=0, trigger_int2=False) → None

start = time.ticks_ms()
last_sample = start

# --- 60-second tilt / tap / free-fall monitor ---
# The board is tilted, double-tapped and dropped within the 60 s window.
# Between events the vector magnitude sits at ≈1.00 g (gravity).
while time.ticks_diff(time.ticks_ms(), start) < 60_000:
    now = time.ticks_ms()

    # Drain the FIFO every 100 ms and characterise the average attitude.
    if time.ticks_diff(now, last_sample) >= 100:
        samples = accel.read_fifo()                         # Drain FIFO, (count=None) → list(tuple(float, float, float)) g
        level = accel.fifo_level()                          # Read FIFO level, () → int
        if samples:
            n = len(samples)
            ax = sum(s[0] for s in samples) / n
            ay = sum(s[1] for s in samples) / n
            az = sum(s[2] for s in samples) / n
            mag = math.sqrt(ax * ax + ay * ay + az * az)
            pitch = math.degrees(math.atan2(ax, math.sqrt(ay * ay + az * az)))
            roll = math.degrees(math.atan2(ay, az))
            temp = accel.read_temperature_delta()           # Read relative temperature, () → int °C
            print('pitch={:+6.1f} roll={:+6.1f} |a|={:.3f} g  T={} °C  fifo={}'.format(
                pitch, roll, mag, temp, level))

        status = accel.poll_interrupt()                     # Read interrupt status, () → int
        if status & LIS3DHSource.SOURCE_CLICK:
            print('DOUBLE TAP')
        if status & LIS3DHSource.SOURCE_IA1:
            print('FREE FALL')
        last_sample = now

    time.sleep_ms(10)

print('done')
