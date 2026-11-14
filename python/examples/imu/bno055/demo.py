from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.bno055 import BNO055Full
import time

connection = I2CConnection(0x29)
imu = BNO055Full(connection)                               # Create BNO055 driver, (connection) → None

# --- Identify the part ---
info = imu.revisions()                                     # Read revisions, () → dict
print('chip 0x%02X, SW %d.%d, bootloader %d' % (info['chip_id'], info['sw_revision'] >> 8, info['sw_revision'] & 0xFF, info['bootloader']))
print('unique id:', imu.unique_id().hex())                 # Read unique ID, () → bytes

# --- Orientation monitor until fully calibrated (move the board in a figure eight) ---
while True:
    heading, roll, pitch = imu.euler()                     # Read fused Euler angles, () → (float, float, float) rad
    w, x, y, z = imu.quaternion()                          # Read orientation quaternion, () → (float, float, float, float)
    lx, ly, lz = imu.linear_acceleration()                 # Read gravity-free acceleration, () → (float, float, float) m/s²
    gx, gy, gz = imu.gravity()                             # Read gravity vector, () → (float, float, float) m/s²
    cal = imu.calibration_status()                         # Read calibration status, () → (int, int, int, int)
    print('euler %.2f %.2f %.2f  quat %.3f %.3f %.3f %.3f  lin %.2f %.2f %.2f  grav %.2f %.2f %.2f  cal %s' % (
        heading, roll, pitch, w, x, y, z, lx, ly, lz, gx, gy, gz, cal))
    if imu.is_calibrated():                                # Check full calibration, () → bool
        break
    time.sleep(0.1)

# --- Save the calibration, relative mode for 5 s, restore it ---
blob = imu.get_calibration()                               # Read offsets and radii, () → bytes
print('calibration:', blob.hex())
imu.set_mode(BNO055Full.MODE_IMU)                          # Select operation mode, (mode) → None
print('mode', imu.get_mode())                              # Read operation mode, () → int
time.sleep(5)
imu.set_mode(BNO055Full.MODE_NDOF)                         # Select operation mode, (mode) → None
imu.set_calibration(blob)                                  # Restore offsets and radii, (data) → None

# --- Self-test and system status ---
print('self-test:', imu.self_test())                       # Run self-test, () → dict
print('temperature: %d C' % imu.temperature())             # Read temperature, () → int °C
imu.set_temperature_source('gyroscope')                    # Select temperature sensor, (source) → None
imu.set_axis_remap(0x24, 0x00)                             # Remap axes, (config, sign) → None
imu.set_power_mode('normal')                               # Select power mode, (mode) → None
imu.use_external_crystal(False)                            # Select clock source, (on) → None

# --- Raw sensors in AMG mode ---
imu.set_mode(BNO055Full.MODE_AMG)                          # Select operation mode, (mode) → None
ax, ay, az = imu.acceleration()                            # Read raw accelerometer, () → (float, float, float) m/s²
mx, my, mz = imu.magnetic_field()                          # Read raw magnetometer, () → (float, float, float) µT
rx, ry, rz = imu.angular_rate()                            # Read raw gyroscope, () → (float, float, float) rad/s
print('acc %.2f %.2f %.2f  mag %.1f %.1f %.1f  gyr %.3f %.3f %.3f' % (ax, ay, az, mx, my, mz, rx, ry, rz))

# --- Interrupts: accelerometer/fusion data ready ---
imu.set_mode(BNO055Full.MODE_NDOF)                         # Select operation mode, (mode) → None
imu.enable_interrupt(BNO055Full.SOURCE_ACC_BSX_DRDY)       # Enable interrupt source, (source) → None
imu.on_interrupt(lambda s: print('INT 0x%02X' % s))        # Subscribe to interrupts, (callback, int_pin=None) → None
time.sleep(0.5)
imu.off_interrupt()                                        # Unsubscribe, () → None
imu.disable_interrupt(BNO055Full.SOURCE_ACC_BSX_DRDY)      # Disable interrupt source, (source) → None
print('interrupt status 0x%02X' % imu.poll_interrupt())    # Poll interrupt status, () → int

status, error = imu.system_status()                        # Read system status, () → (int, int)
print('system status %d, error %d' % (status, error))
imu.reset()                                                # Reset the chip, () → None
