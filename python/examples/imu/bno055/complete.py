from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.bno055 import BNO055Full
import time

connection = I2CConnection(0x29)
imu = BNO055Full(connection)                               # Create BNO055 driver, (connection) → None

heading, roll, pitch = imu.euler()                         # Read fused Euler angles, () → (float, float, float) rad
                                                           # burst read of 0x1A…0x1F, raw / 900
w, x, y, z = imu.quaternion()                              # Read orientation quaternion, () → (float, float, float, float)
                                                           # raw / 16384
lx, ly, lz = imu.linear_acceleration()                     # Read gravity-free acceleration, () → (float, float, float) m/s²
                                                           # raw / 100
gx, gy, gz = imu.gravity()                                 # Read gravity vector, () → (float, float, float) m/s²
                                                           # raw / 100
ax, ay, az = imu.acceleration()                            # Read raw accelerometer, () → (float, float, float) m/s²
                                                           # raw / 100
mx, my, mz = imu.magnetic_field()                          # Read raw magnetometer, () → (float, float, float) µT
                                                           # raw / 16
rx, ry, rz = imu.angular_rate()                            # Read raw gyroscope, () → (float, float, float) rad/s
                                                           # raw / 900
t = imu.temperature()                                      # Read temperature, () → int °C
                                                           # signed 8 bit, 1 LSB = 1 °C
sys_c, gyr_c, acc_c, mag_c = imu.calibration_status()      # Read calibration status, () → (int, int, int, int)
                                                           # each 0 (uncalibrated) … 3 (calibrated)
done = imu.is_calibrated()                                 # Check full calibration, () → bool
                                                           # true when all four values are 3

mode = imu.get_mode()                                      # Read operation mode, () → int
                                                           # 0x0C = NDOF
imu.set_mode(BNO055Full.MODE_IMU)                          # Select operation mode, (mode) → None
                                                           # via CONFIGMODE, 19 ms + 7 ms waits
imu.set_mode(BNO055Full.MODE_NDOF)                         # Select operation mode, (mode) → None
                                                           # back to 9-DOF fusion

blob = imu.get_calibration()                               # Read offsets and radii, () → bytes
                                                           # 22 bytes, 0x55…0x6A, read in CONFIGMODE
imu.set_calibration(blob)                                  # Restore offsets and radii, (data) → None
                                                           # 22 bytes, written in CONFIGMODE
imu.set_axis_remap(0x24, 0x00)                             # Remap axes, (config, sign) → None
                                                           # 0x24/0x00 = identity placement
imu.set_power_mode('normal')                               # Select power mode, (mode) → None
                                                           # 'normal', 'low_power' or 'suspend'
imu.set_temperature_source('accelerometer')                # Select temperature sensor, (source) → None
                                                           # 'accelerometer' or 'gyroscope'
imu.use_external_crystal(False)                            # Select clock source, (on) → None
                                                           # internal oscillator unless a crystal is fitted

result = imu.self_test()                                   # Run self-test, () → dict
                                                           # {'acc', 'mag', 'gyr', 'mcu'} booleans
status, error = imu.system_status()                        # Read system status, () → (int, int)
                                                           # SYS_STATUS 5 = fusion running, SYS_ERR 0 = none
info = imu.revisions()                                     # Read revisions, () → dict
                                                           # chip/acc/mag/gyr IDs, SW revision, bootloader
uid = imu.unique_id()                                      # Read unique ID, () → bytes
                                                           # 16 bytes from page 1

imu.enable_interrupt(BNO055Full.SOURCE_ACC_BSX_DRDY)       # Enable interrupt source, (source) → None
                                                           # INT_EN and INT_MSK on page 1
status = imu.poll_interrupt()                              # Poll interrupt status, () → int
                                                           # INT_STA, cleared on read
imu.on_interrupt(lambda s: print('INT status 0x%02X' % s)) # Subscribe to interrupts, (callback, int_pin=None) → None
                                                           # uses an INT pin or a 100 ms polling task
time.sleep(0.5)
imu.off_interrupt()                                        # Unsubscribe, () → None
                                                           # stops the callback
imu.disable_interrupt(BNO055Full.SOURCE_ACC_BSX_DRDY)      # Disable interrupt source, (source) → None
                                                           # clears the INT_EN and INT_MSK bits

imu.reset()                                                # Reset the chip, () → None
                                                           # RST_SYS, waits 650 ms, re-enters NDOF
