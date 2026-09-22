from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.mpu9255 import MPU9255Full
import math
import time

connection = I2CConnection(0x68)
mag_connection = I2CConnection(0x0C)  # AK8963 lives at 0x0C on the same bus, reached via I²C bypass

# --- Configure for motion-triggered wake logger ---
# 64 mg threshold and 31.25 Hz wake-up rate balance sensitivity against spurious
# wake-ups from vibration; once motion fires, the full 6-axis sensor suite
# (gyro + mag at 100 Hz) is re-enabled to capture a 5-second tilt/heading burst.
imu = MPU9255Full(connection, mag_connection)              # Create MPU9255 driver, (connection, mag_connection) → None
imu.configure_wake_on_motion(threshold_mg=64, odr_hz=31.25) # Configure wake-on-motion, (threshold_mg=64, odr_hz=31.25) → None

last_heartbeat = time.ticks_ms()

while True:
    # --- Idle phase: motion poll at 5 Hz, "sleeping…" heartbeat at ~1 Hz ---
    # configure_wake_on_motion already disabled the gyro and put the chip
    # in CYCLE=1 duty-cycled mode; polling motion_detected() reflects that
    # state without forcing any further register writes.
    while not imu.motion_detected():                       # Check motion detected, () → bool
        now = time.ticks_ms()
        if time.ticks_diff(now, last_heartbeat) >= 1000:
            print('sleeping...')
            last_heartbeat = now
        time.sleep_ms(200)

    # --- Wake phase: re-arm the full 6-axis + mag stack ---
    # PWR_MGMT_1=0x01 clears CYCLE; PWR_MGMT_2=0x00 re-enables all three gyro axes.
    imu.set_sleep(sleep=False)                            # Wake from sleep, (sleep=True) → None
    imu._write_reg(imu._REG_PWR_MGMT_2, 0x00)             # Reset PWR_MGMT_2, (value=0x00) → None
    imu.configure_gyro(full_scale=1)                      # Configure gyro range, (full_scale=0) → None
    imu.configure_accel(full_scale=1)                     # Configure accel range, (full_scale=0) → None
    imu.enable_mag(bits=16, mode=6)                       # Initialize magnetometer, (bits=16, mode=6) → None

    # --- Capture a 5-second tilt/heading burst at ~10 Hz ---
    # Roll/pitch from gravity (quasi-static) + heading from mag (no tilt comp).
    print('--- motion detected ---')
    end = time.ticks_add(time.ticks_ms(), 5000)
    while time.ticks_diff(end, time.ticks_ms()) > 0:
        while not imu.data_ready():                       # Check data ready flag, () → bool
            pass

        ax, ay, az = imu.accel()                          # Read 3-axis acceleration, () → (float, float, float) m/s²
        gx, gy, gz = imu.gyro()                           # Read 3-axis angular rate, () → (float, float, float) rad/s
        mx, my, mz = imu.mag()                            # Read 3-axis magnetic field, () → (float, float, float) µT

        roll  = math.atan2(ay, az) * 180.0 / math.pi
        pitch = math.atan2(-ax, math.sqrt(ay * ay + az * az)) * 180.0 / math.pi
        heading = math.atan2(my, mx) * 180.0 / math.pi

        print('%-8.1f %-8.1f %-8.1f  |g|=%.2f' % (roll, pitch, heading, math.sqrt(gx * gx + gy * gy + gz * gz)))
        time.sleep_ms(100)

    # --- Return to low-power wake-on-motion mode ---
    imu.configure_wake_on_motion(threshold_mg=64, odr_hz=31.25) # Configure wake-on-motion, (threshold_mg=64, odr_hz=31.25) → None
    last_heartbeat = time.ticks_ms()