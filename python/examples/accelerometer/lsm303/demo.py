"""Demo for the LSM303DLHC — tilt-compensated compass with tap and free-fall detection.

Configures ±2 g at 50 Hz (high-resolution) and ±130 µT at 15 Hz. A double tap
on Z and a free-fall event are routed to INT1, and the FIFO runs in stream
mode with a 16-sample watermark. Every second the FIFO is drained and
averaged, and the tilt-compensated heading (with a 16-point compass name),
pitch, roll, magnetic field magnitude and relative temperature are printed.
Runs for 60 seconds.

Place the board flat and rotate it slowly to watch the heading track; tilt
it to change pitch and roll. Double-tap Z to see "DOUBLE TAP"; drop or
toss the board to see "FREE FALL".
"""

import math
import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Full, LSM303Source

COMPASS_POINTS = ('N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE',
                  'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW')
RUN_SECONDS = 60
POLL_MS = 50

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
accel_conn = I2CConnection(i2c, 0x19)                   # Create accelerometer connection, (bus, addr=0x19) → None
mag_conn = I2CConnection(i2c, 0x1E)                     # Create magnetometer connection, (bus, addr=0x1E) → None
compass = LSM303Full(accel_conn, mag_conn)              # Create LSM303 Full driver, (accel_connection, mag_connection) → None

# --- Configure accelerometer and magnetometer ---
# ±2 g at 50 Hz in high-resolution mode gives 1 mg/LSB, plenty for tilt.
# ±130 µT at 15 Hz is the most sensitive magnetometer range, with the lowest noise.
compass.set_range(2)                                    # Set accelerometer range, (range_g=2) → None g
compass.set_mode(2)                                     # Set accelerometer mode, (mode=2) → None
compass.set_data_rate(50)                               # Set accelerometer data rate, (rate_hz=100) → None Hz
compass.set_mag_range(130)                              # Set magnetometer range, (range_ut) → None µT
compass.set_mag_data_rate(15)                           # Set magnetometer data rate, (rate_hz) → None Hz

# --- Arm tap and free-fall detection on INT1 ---
# A double tap on Z (mask 4) at 0.8 g; free-fall at 0.35 g for 60 ms, latched
# so the flag stays set until read.
compass.set_click(double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200)  # Configure click, (single=0, double=0, threshold_g=0.5, time_limit_ms=10, latency_ms=20, window_ms=50) → None g, ms
compass.set_free_fall(generator=1, threshold_g=0.35, duration_ms=60)  # Configure free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → None g, ms
compass.set_latch(1, True)                              # Latch generator 1, (generator, enabled) → None
compass.enable_interrupt(LSM303Source.SOURCE_CLICK, 1)  # Route interrupt source, (source, pin=1) → None
compass.enable_interrupt(LSM303Source.SOURCE_IA1, 1)    # Route interrupt source, (source, pin=1) → None

# --- FIFO in stream mode with a 16-sample watermark ---
# The FIFO keeps the most recent 32 sample sets; draining once per second
# averages the last second of motion, which smooths the tilt estimate.
compass.set_fifo(2, watermark=16)                       # Configure FIFO, (mode, watermark=0, trigger_int2=False) → None


def compass_name(heading_deg):
    index = int((heading_deg + 11.25) / 22.5) % 16
    return COMPASS_POINTS[index]


def average(samples):
    n = float(len(samples))
    return (sum(s[0] for s in samples) / n,
            sum(s[1] for s in samples) / n,
            sum(s[2] for s in samples) / n)


start = time.time()                                     # Read wall clock, () → float s
                                                         # used to bound the run to RUN_SECONDS
next_report = start + 1.0

while time.time() - start < RUN_SECONDS:
    # --- Interrupts: check every POLL_MS so taps are not missed ---
    status = compass.poll_interrupt()                   # Poll interrupt status, () → int
    if status & LSM303Source.SOURCE_CLICK:
        print('DOUBLE TAP')
    if status & LSM303Source.SOURCE_IA1:
        print('FREE FALL')

    if time.time() >= next_report:
        next_report += 1.0

        # --- Once per second: drain the FIFO and average ---
        # Fall back to a single read when the FIFO is empty so the report always has data.
        samples = compass.read_fifo()                   # Drain FIFO, (count=None) → list[tuple(float, float, float)] g
        if samples:
            ax, ay, az = average(samples)
        else:
            ax, ay, az = compass.read_acceleration()    # Read acceleration, () → tuple(float, float, float) g

        # --- Magnetic field and tilt-compensated heading ---
        mx, my, mz = compass.read_magnetic()            # Read magnetic field, () → tuple(float, float, float) µT
        heading = compass.heading(tilt_compensate=True) # Read compass heading, (tilt_compensate=True) → float °
        magnitude = math.sqrt(mx * mx + my * my + mz * mz)

        # --- Pitch and roll from the averaged acceleration (same formulas as heading) ---
        roll = math.atan2(ay, az)
        pitch = math.atan2(-ax, ay * math.sin(roll) + az * math.cos(roll))
        temp = compass.read_temperature_delta()         # Read relative temperature, () → float °C

        print('heading {:6.1f} deg ({:>3s})  pitch {:+6.1f}  roll {:+6.1f}  |B| {:6.1f} uT  T {:+.1f} C'.format(
            heading, compass_name(heading),
            math.degrees(pitch), math.degrees(roll), magnitude, temp))

    time.sleep(POLL_MS / 1000.0)

compass.set_fifo(0)                                     # Configure FIFO, (mode=0) → None
compass.disable_interrupt(LSM303Source.SOURCE_IA1, 1)   # Unroute interrupt source, (source, pin=1) → None
print('done')
