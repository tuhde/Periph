from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.qmi8658a import QMI8658AFull
import math
import time

connection = I2CConnection(0x6B)
imu = QMI8658AFull(connection)                             # Create QMI8658A driver, (connection) → None

# --- Identify the part and run the built-in checks ---
# The revision, firmware version and USID identify the silicon; the
# self-tests need the board lying still and the gyro calibration trims the
# X/Y gains before real use.
info = imu.device_info()                                   # Read device info, () → dict
print('revision 0x%02X firmware %s usid %s' % (info['revision'], info['firmware'].hex(), info['usid'].hex()))
ok, _ = imu.self_test_accel()                              # Run accel self-test, () → (bool, (float, float, float)) m/s²
print('accel self-test:', 'PASS' if ok else 'FAIL')
ok, _ = imu.self_test_gyro()                               # Run gyro self-test, () → (bool, (float, float, float)) rad/s
print('gyro self-test:', 'PASS' if ok else 'FAIL')
ok, status = imu.calibrate_gyro()                          # Calibrate gyroscope, () → (bool, int)
print('gyro calibration:', 'OK' if ok else 'FAILED 0x%02X' % status)

# --- Configure ±4 g / ±512 dps at 112.1 Hz ---
# ODR code 6 gives 112.1 Hz for both sensors; mode 3 filters keep the
# noise bandwidth near 15 Hz.
imu.configure_accel(full_scale=1, odr=6)                   # Configure accelerometer, (full_scale=0, odr=5) → None
imu.configure_gyro(full_scale=5, odr=6)                    # Configure gyroscope, (full_scale=4, odr=5) → None
imu.configure_filters(True, 3, True, 3)                    # Configure low-pass filters, (accel_filter=True, accel_mode=3, gyro_filter=True, gyro_mode=3) → None

# --- Step counting and tap detection ---
# The pedometer defaults suit ~50 Hz, so the window parameters are scaled
# for 112 Hz; the tap engine runs best above 200 Hz and is only a coarse
# detector at this rate.
imu.configure_pedometer(sample_count=112, time_up=450, time_low=45, entry_count=10, report_every=1)  # Configure pedometer, (sample_count=50, …) → None
imu.enable_pedometer(True)                                 # Enable pedometer, (enabled=True) → None
imu.configure_tap()                                        # Configure tap engine, (priority=0, …) → None
imu.enable_tap(True)                                       # Enable tap engine, (enabled=True) → None

# --- Stream into the FIFO ---
# Stream mode with 32 samples and a watermark of 16 is drained once per
# second; each sample is a 12-byte frame (accel XYZ then gyro XYZ).
imu.configure_fifo(mode=2, size=1, watermark=16)           # Configure FIFO, (mode=2, size=3, watermark=0) → None
time.sleep(0.2)

start = time.time()
last_fifo = start
while time.time() - start < 60:
    if imu.data_ready():                                   # Check for new data, () → bool
        (ax, ay, az), (gx, gy, gz), temp = imu.read_all()  # Read one coherent sample, () → ((float, float, float), (float, float, float), float) m/s², rad/s, °C
        roll = math.degrees(math.atan2(ay, az))
        pitch = math.degrees(math.atan2(-ax, math.sqrt(ay * ay + az * az)))
        mag = math.sqrt(gx * gx + gy * gy + gz * gz)
        print('roll %6.1f pitch %6.1f |gyro| %.3f rad/s  %.1f C  steps %d' % (
            roll, pitch, mag, temp, imu.step_count()))     # Read step count, () → int
    status = imu.poll_interrupt()                          # Poll interrupt status, () → int
    if status & QMI8658AFull.SOURCE_TAP:
        taps, axis, negative = imu.tap_status()            # Read tap event, () → (int, int, bool)
        print('TAP %s on axis %s' % ('double' if taps == 2 else 'single', 'XYZ'[axis - 1] if axis else '?'))
    if time.time() - last_fifo >= 1.0:
        last_fifo = time.time()
        n = imu.fifo_count()                               # Read FIFO fill, () → int bytes
        data = imu.read_fifo()                             # Drain the FIFO, (count=None) → bytes
        print('FIFO: %d bytes (%d), %d frames' % (n, len(data), len(data) // 12))
    time.sleep(0.1)
