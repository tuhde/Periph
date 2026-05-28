from machine import I2C
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.imu.mpu6050 import MPU6050Full
import math
import time

i2c = I2C(2, freq=400000)
transport = I2CTransport(i2c, 0x68)

# --- Configure for motion logging with moderate dynamic range ---
# ±4g captures typical tilting and handling forces without clipping;
# ±500 dps covers fast rotations while retaining sub-degree resolution.
imu = MPU6050Full(transport)                             # Create MPU6050 driver, (transport) → None
imu.configure_accel(full_scale=1)                        # Configure accel range, (full_scale=0) → None
imu.configure_gyro(full_scale=1)                         # Configure gyro range, (full_scale=0) → None

print('%-8s %-8s %-10s %-10s' % ('roll', 'pitch', '|accel|', '|gyro|'))

while True:
    # gate reads on data_ready so each sample reflects a fresh conversion
    while not imu.data_ready():                          # Check data ready flag, () → bool
        pass

    ax, ay, az = imu.accel()                             # Read 3-axis acceleration, () → (float, float, float) m/s²
    gx, gy, gz = imu.gyro()                              # Read 3-axis angular rate, () → (float, float, float) rad/s

    # --- Compute tilt angles from the accelerometer gravity vector ---
    # roll and pitch are reliable when the device is quasi-static;
    # gyro magnitude indicates how fast the board is being rotated.
    roll  = math.atan2(ay, az) * 180.0 / math.pi
    pitch = math.atan2(-ax, math.sqrt(ay * ay + az * az)) * 180.0 / math.pi
    accel_mag = math.sqrt(ax * ax + ay * ay + az * az)
    gyro_mag  = math.sqrt(gx * gx + gy * gy + gz * gz)

    print('%-8.1f %-8.1f %-10.3f %-10.3f' % (roll, pitch, accel_mag, gyro_mag))
    time.sleep_ms(100)
