from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.mpu9250 import MPU9250Full
import math
import time

connection = I2CConnection(0x68)
mag_connection = I2CConnection(0x0C)  # AK8963 lives at 0x0C on the same bus, reached via I²C bypass

# --- Configure for tilt and heading estimation ---
# ±4g / ±500dps trade sensitivity for headroom against sharper motion than
# the ±2g / ±250dps defaults tolerate; 16-bit continuous magnetometer mode
# keeps a fresh heading available on every poll.
imu = MPU9250Full(connection, mag_connection)              # Create MPU9250 driver, (connection, mag_connection) → None
imu.configure_accel(full_scale=1)                        # Configure accel range, (full_scale=0) → None
imu.configure_gyro(full_scale=1)                         # Configure gyro range, (full_scale=0) → None
imu.enable_mag(bits=16, mode=6)                          # Initialize magnetometer, (bits=16, mode=6) → None

print('%-8s %-8s %-8s %-10s %-10s' % ('roll', 'pitch', 'heading', '|accel|', '|gyro|'))

while True:
    # gate reads on data_ready so each sample reflects a fresh conversion
    while not imu.data_ready():                          # Check data ready flag, () → bool
        pass

    ax, ay, az = imu.accel()                             # Read 3-axis acceleration, () → (float, float, float) m/s²
    gx, gy, gz = imu.gyro()                              # Read 3-axis angular rate, () → (float, float, float) rad/s
    mx, my, mz = imu.mag()                               # Read 3-axis magnetic field, () → (float, float, float) µT

    # --- Compute tilt angles from the accelerometer gravity vector ---
    # roll and pitch are reliable when the device is quasi-static;
    # gyro magnitude indicates how fast the board is being rotated.
    roll  = math.atan2(ay, az) * 180.0 / math.pi
    pitch = math.atan2(-ax, math.sqrt(ay * ay + az * az)) * 180.0 / math.pi

    # --- Compute magnetic heading (simplified, no tilt compensation) ---
    # Magnetometer axes differ from accel/gyro axes; user must account for this in fusion.
    heading = math.atan2(my, mx) * 180.0 / math.pi

    accel_mag = math.sqrt(ax * ax + ay * ay + az * az)
    gyro_mag  = math.sqrt(gx * gx + gy * gy + gz * gz)

    print('%-8.1f %-8.1f %-8.1f %-10.3f %-10.3f' % (roll, pitch, heading, accel_mag, gyro_mag))
    time.sleep_ms(100)