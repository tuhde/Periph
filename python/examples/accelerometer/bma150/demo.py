"""Demo for the BMA150 — free-fall / shock logger.

Configure the chip for ±8 g, 190 Hz bandwidth; arm the low-g interrupt
(0.4 g, 40 ms, latched) and the high-g interrupt (4 g, 2 ms, latched).
Poll :meth:`poll_interrupt` every 50 ms and print a timestamped line
whenever ``STATUS_LG_LATCHED`` or ``STATUS_HG_LATCHED`` is set, plus the
latest x, y, z and temperature, then call :meth:`clear_interrupt`. Between
events print a 1 Hz heartbeat with magnitude (≈1.00 g at rest). Run for
60 s then exit.
"""

import math
import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.bma150 import BMA150Full

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
connection = I2CConnection(i2c, 0x38)
accel = BMA150Full(connection)                             # Create BMA150 driver, (connection)

# --- Configure ±8 g / 190 Hz and arm LG + HG latched interrupts ---
# ±8 g gives 64 LSB/g, plenty of headroom for shock detection. 190 Hz
# bandwidth is wide enough to capture a 2 ms high-g spike without
# aliasing. Latched interrupts free the polling loop from having to
# catch a transient.
accel.set_range(8)                                        # Set measurement range, (range_g=2) → None g
accel.set_bandwidth(190)                                  # Set bandwidth, (bandwidth_hz=25) → None Hz
accel.set_latch(True)                                     # Set latched interrupts, (enabled=False) → None
accel.set_low_g(0.4, 40)                                  # Configure low-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → None g, ms
accel.set_high_g(4.0, 2)                                  # Configure high-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → None g, ms

start = time.ticks_ms()
last_heartbeat = start
last_poll = start

# --- 60-second free-fall / shock logger ---
# User is expected to drop or shake the board at some point during
# the 60 s window. Between events the magnitude sits at ≈1.00 g
# (gravity). Each latched interrupt is reported with a timestamp,
# the latest (x, y, z), temperature, and a free-fall or shock tag.
while time.ticks_diff(time.ticks_ms(), start) < 60_000:
    now = time.ticks_ms()

    # 1 Hz heartbeat with magnitude (≈1.00 g at rest).
    if time.ticks_diff(now, last_heartbeat) >= 1000:
        x, y, z = accel.read()                            # Read 3-axis acceleration, () → tuple(float, float, float) g
        mag = math.sqrt(x * x + y * y + z * z)
        temp = accel.read_temperature()                   # Read temperature, () → float °C
        print('{:5d}  x={:+.3f}  y={:+.3f}  z={:+.3f}  |a|={:.3f} g  T={:.1f} °C'.format(
            time.ticks_diff(now, start) // 1000, x, y, z, mag, temp))
        last_heartbeat = now

    # 50 ms interrupt poll.
    if time.ticks_diff(now, last_poll) >= 50:
        status = accel.poll_interrupt()                   # Read STATUS, () → int
        if status & 0x08:                                 # check STATUS_LG_LATCHED (bit 3)
            x, y, z = accel.read()                        # Read 3-axis acceleration, () → tuple(float, float, float) g
            temp = accel.read_temperature()               # Read temperature, () → float °C
            print('{:5d}  FREE FALL detected  x={:+.3f}  y={:+.3f}  z={:+.3f}  T={:.1f} °C'.format(
                time.ticks_diff(now, start), x, y, z, temp))
            accel.clear_interrupt()                       # Clear latched interrupts, () → None
        if status & 0x04:                                 # check STATUS_HG_LATCHED (bit 2)
            x, y, z = accel.read()                        # Read 3-axis acceleration, () → tuple(float, float, float) g
            temp = accel.read_temperature()               # Read temperature, () → float °C
            print('{:5d}  SHOCK detected     x={:+.3f}  y={:+.3f}  z={:+.3f}  T={:.1f} °C'.format(
                time.ticks_diff(now, start), x, y, z, temp))
            accel.clear_interrupt()                       # Clear latched interrupts, () → None
        last_poll = now

    time.sleep_ms(10)

print('done')
