"""Demo for the LSM303 — a tilt-compensated electronic compass with gesture
detection.

The sensor streams into the FIFO in stream mode; every second the samples
are drained and averaged, the magnetic field is read and a tilt-compensated
heading is printed together with the pitch and roll of the board. A double
tap on the Z axis and a free-fall event are reported as they happen.
"""

import math
import time
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import (
    LSM303Full, LSM303Source, I2C_ADDRESS_ACCEL, I2C_ADDRESS_MAG)

# The 16-point compass rose used for the heading output.
DIRECTIONS = ('N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE',
              'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW')

RUN_SECONDS = 60

i2c = I2C(0, sda=Pin(21), scl=Pin(22), freq=400_000)
accel_connection = I2CConnection(i2c, I2C_ADDRESS_ACCEL)
mag_connection = I2CConnection(i2c, I2C_ADDRESS_MAG)
compass = LSM303Full(accel_connection, mag_connection)    # Create LSM303 Full driver, (accel_connection, mag_connection)

# --- Configure the sensor for a responsive compass ---
# 50 Hz gives a fresh sample every 20 ms without flooding the FIFO, and the
# high-resolution mode keeps 1 mg/LSB so the tilt compensation stays precise.
compass.set_data_rate(50)                                 # Set output data rate, (rate_hz 0..5376) → None
compass.set_mode(LSM303Full.MODE_HIGH_RESOLUTION)         # Set operating mode, (mode 0|1|2) → None
compass.set_mag_range(130)                                # Set magnetometer range, (range_ut 130..810) → None
compass.set_mag_data_rate(15)                             # Set magnetometer data rate, (rate_hz 0.75..220) → None

# --- Arm the gestures ---
# A double tap on the Z axis and a free-fall event both end up on INT1.
# The latch keeps the line asserted until the source register is read, so a
# short tap is never missed between two polling rounds.
compass.set_click(double=0x04, threshold_g=0.8,            # Configure click detector, (single=0, double=0, threshold_g=0.5, time_limit_ms=10, latency_ms=20, window_ms=50) → None
                  time_limit_ms=20, latency_ms=40, window_ms=200)  # double tap on the Z axis
compass.set_free_fall(generator=1, threshold_g=0.35, duration_ms=60)  # Arm free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → None
compass.set_latch(1, True)                                # Latch the interrupt, (generator 1|2, enabled) → None
compass.enable_interrupt(LSM303Source.SOURCE_CLICK, 1)    # Route an interrupt, (source, pin=1) → None
compass.enable_interrupt(LSM303Source.SOURCE_IA1, 1)      # Route an interrupt, (source, pin=1) → None

# --- Collect the samples in the FIFO ---
# Stream mode keeps the newest 32 sample sets; the watermark interrupts as
# soon as 16 of them have piled up.
compass.set_fifo(LSM303Full.FIFO_STREAM, watermark=16)    # Configure FIFO, (mode 0..3, watermark=0, trigger_int2=False) → None

start = time.time()
while time.time() - start < RUN_SECONDS:
    # --- Average the samples the FIFO collected ---
    # Averaging over the whole burst cancels most of the vibration noise a
    # handheld compass would otherwise show.
    samples = compass.read_fifo()                         # Read FIFO, (count=None) → list of (x, y, z) float g
    if samples:
        ax = sum(s[0] for s in samples) / len(samples)
        ay = sum(s[1] for s in samples) / len(samples)
        az = sum(s[2] for s in samples) / len(samples)
    else:
        ax, ay, az = compass.read_acceleration()          # Read acceleration, () → (x, y, z) float g

    # --- Compute the heading and the tilt ---
    heading = compass.heading()                           # Read the compass heading, (tilt_compensate=True) → float °
                                                         # tilt-compensated with the accelerometer
    roll = math.degrees(math.atan2(ay, az))
    pitch = math.degrees(math.atan2(-ax, math.sqrt(ay * ay + az * az)))
    mx, my, mz = compass.read_magnetic()                  # Read magnetic field, () → (x, y, z) float µT
    magnitude = math.sqrt(mx * mx + my * my + mz * mz)
    temperature = compass.read_temperature_delta()        # Read relative temperature, () → float °C
    name = DIRECTIONS[int((heading + 11.25) / 22.5) % 16]

    print('heading {:6.1f} deg ({:3s})  pitch {:6.1f} deg  roll {:6.1f} deg  '
          'field {:6.1f} uT  temp {:5.1f} C'.format(
              heading, name, pitch, roll, magnitude, temperature))

    # --- Report the gestures ---
    status = compass.poll_interrupt()                     # Read interrupt status, () → int mask
                                                         # reading the source registers clears the latched flags
    if status & LSM303Source.SOURCE_CLICK:
        print('DOUBLE TAP')
    if status & LSM303Source.SOURCE_IA1:
        print('FREE FALL')

    time.sleep(1)
