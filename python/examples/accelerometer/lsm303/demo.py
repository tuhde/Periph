"""Demo for the LSM303 — tilt-compensated compass with tap and free-fall detection.

Configures ±2 g at 50 Hz in high-resolution mode and ±130 µT at 15 Hz, arms a
double tap on Z and a latched free-fall generator, routes them to INT1 and
puts the FIFO in stream mode with a 16-sample watermark. Every second the
FIFO is drained and averaged, the magnetic field is read, and the
tilt-compensated heading is printed as degrees plus a 16-point compass name,
with pitch / roll in degrees, the field magnitude and the relative
temperature. When the polled interrupt status reports SOURCE_CLICK the script
prints DOUBLE TAP; SOURCE_IA1 prints FREE FALL. Runs 60 s and exits.
"""

import math
import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Full

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
accel_conn = I2CConnection(i2c, 0x19)
mag_conn = I2CConnection(i2c, 0x1E)
compass = LSM303Full(accel_conn, mag_conn)              # Create LSM303 Full driver, (accel_connection, mag_connection)

# --- Configure the e-compass: ±2 g at 50 Hz, ±130 µT at 15 Hz ---
# High-resolution mode keeps 1 mg/LSB tilt resolution; the 50 Hz accelerometer
# rate gives the tap / free-fall detectors a 20 ms time base (1 LSb = 1/ODR).
compass.set_data_rate(50)                               # Set accelerometer data rate, (rate_hz) → None Hz
compass.set_mag_range(130)                              # Set magnetometer range, (range_ut) → None µT

# --- Arm double tap on Z and a latched free-fall generator ---
# The double tap needs a short time limit and a wide window; free-fall is
# latched so a brief drop is never missed between polls.
compass.set_click(double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200)
                                                         # Configure click detection, (single=0, double=0, threshold_g=0.5 g, time_limit_ms=10 ms, latency_ms=20 ms, window_ms=50 ms) → None
compass.set_free_fall(1, 0.35, 60)                      # Configure free-fall, (generator=1, threshold_g=0.35 g, duration_ms=30 ms) → None
compass.set_latch(1, True)                              # Set interrupt latching, (generator, enabled) → None
compass.enable_interrupt(LSM303Full.SOURCE_CLICK, 1)    # Route interrupt source, (source, pin=1) → None
compass.enable_interrupt(LSM303Full.SOURCE_IA1, 1)      # Route interrupt source, (source, pin=1) → None

# --- Stream-mode FIFO with a 16-sample watermark for clean averaging ---
compass.set_fifo(2, 16)                                 # Configure FIFO, (mode, watermark=0, trigger_int2=False) → None

COMPASS_POINTS = ('N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE',
                  'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW')

start = time.ticks_ms()
while time.ticks_diff(time.ticks_ms(), start) < 60000:
    samples = compass.read_fifo()                       # Drain the FIFO, (count=None) → list[tuple(float, float, float)] g
    if samples:
        ax = sum(s[0] for s in samples) / len(samples)
        ay = sum(s[1] for s in samples) / len(samples)
        az = sum(s[2] for s in samples) / len(samples)
        pitch = math.degrees(math.atan2(-ax, math.sqrt(ay * ay + az * az)))
        roll = math.degrees(math.atan2(ay, az))
    mx, my, mz = compass.read_magnetic()               # Read 3-axis magnetic field, () → tuple(float, float, float) µT
    hdg = compass.heading()                            # Compute compass heading, (tilt_compensate=True) → float °
    field = math.sqrt(mx * mx + my * my + mz * mz)
    name = COMPASS_POINTS[int((hdg + 11.25) / 22.5) % 16]
    temp = compass.read_temperature_delta()            # Read relative temperature, () → float °C
    print('{:5.1f} deg {}  pitch={:+.1f} roll={:+.1f}  |m|={:.0f} uT  dT={:+.1f} C'.format(
        hdg, name, pitch, roll, field, temp))

    status = compass.poll_interrupt()                  # Poll all enabled interrupt sources, () → int
    if status & LSM303Full.SOURCE_CLICK:
        print('DOUBLE TAP')
    if status & LSM303Full.SOURCE_IA1:
        print('FREE FALL')

    time.sleep_ms(1000)
