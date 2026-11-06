from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.icm20948 import ICM20948Full
import math
import time

connection = I2CConnection(0x68)

# --- Configure for motion and heading monitoring ---
# ±4g / ±500dps give headroom for sharper motion; DLPFCFG 3 and a divider of
# 10 keep a ~100 Hz sample rate with a ~50 Hz noise bandwidth. The
# magnetometer runs through the chip's own I²C master, so one connection
# is enough.
imu = ICM20948Full(connection)                           # Create ICM20948 driver, (connection) → None
imu.configure_accel(full_scale=1)                        # Configure accel range, (full_scale=0) → None
imu.configure_gyro(full_scale=1)                         # Configure gyro range, (full_scale=0) → None
imu.configure_filters(gyro_dlpf=3, accel_dlpf=3)         # Configure low-pass filters, (gyro_dlpf=3, accel_dlpf=3, gyro_filter=True, accel_filter=True) → None
imu.configure_sample_rate(gyro_div=10, accel_div=10)     # Configure sample rate, (gyro_div=10, accel_div=10) → None
imu.enable_mag()                                         # Enable magnetometer, () → None

print('ICM-20948 die temperature: %.1f C' % imu.temperature())  # Read die temperature, () → float °C

# --- Arm wake-on-motion and route it to the interrupt status ---
# A 200 mg threshold flags a bump; poll_interrupt() reports it as
# SOURCE_WAKE_ON_MOTION without needing the INT pin wired.
imu.set_wake_on_motion(threshold_mg=200)                 # Arm wake-on-motion, (threshold_mg, enabled=True) → None
imu.enable_interrupt(ICM20948Full.SOURCE_WAKE_ON_MOTION) # Enable interrupt source, (source) → None

# --- Stream accelerometer + gyro into the FIFO ---
# Stream mode overwrites the oldest data; once per second the FIFO is
# drained and counted in 12-byte frames (accel X, Y, Z, gyro X, Y, Z).
imu.enable_fifo(accel=True, gyro=True)                   # Enable FIFO sources, (accel=True, gyro=True, temp=False) → None
imu.set_fifo_mode(snapshot=False)                        # Select FIFO mode, (snapshot=False) → None

print('%-8s %-8s %-8s %-10s' % ('roll', 'pitch', 'heading', '|gyro|'))

start = time.time()
last_fifo = start
while time.time() - start < 60:
    accel, gyro, temp = imu.read_all()                   # Read one coherent sample, () → ((float, float, float), (float, float, float), float) m/s², rad/s, °C
    ax, ay, az = accel
    gx, gy, gz = gyro
    mx, my, mz = imu.mag()                               # Read 3-axis magnetic field, () → (float, float, float) µT

    # --- Compute roll, pitch and a tilt-compensated heading ---
    # The magnetometer frame is rotated 180° about X relative to the
    # inertial frame, so (mx, -my, -mz) maps it into the accel frame
    # before the tilt compensation rotates it into the horizontal plane.
    roll = math.atan2(ay, az)
    pitch = math.atan2(-ax, math.sqrt(ay * ay + az * az))
    bx, by, bz = mx, -my, -mz
    xh = bx * math.cos(pitch) + bz * math.sin(pitch)
    yh = (bx * math.sin(roll) * math.sin(pitch) + by * math.cos(roll)
          - bz * math.sin(roll) * math.cos(pitch))
    heading = (math.degrees(math.atan2(-yh, xh)) + 360.0) % 360.0
    gyro_mag = math.sqrt(gx * gx + gy * gy + gz * gz)

    print('%-8.1f %-8.1f %-8.1f %-10.3f' % (math.degrees(roll), math.degrees(pitch), heading, gyro_mag))

    status = imu.poll_interrupt()                        # Read interrupt status, () → int
    if status & ICM20948Full.SOURCE_WAKE_ON_MOTION:
        print('MOTION')

    if time.time() - last_fifo >= 1.0:
        last_fifo = time.time()
        n = imu.fifo_count()                             # Read FIFO byte count, () → int
        data = imu.read_fifo()                           # Read FIFO data, (count=None) → bytes
        print('FIFO: %d bytes, %d frames' % (len(data), len(data) // 12))
        _ = n

    time.sleep_ms(100)
