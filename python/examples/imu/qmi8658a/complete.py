from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.qmi8658a import QMI8658AFull
import time

connection = I2CConnection(0x6B)
imu = QMI8658AFull(connection)                             # Create QMI8658A driver, (connection) → None

ax, ay, az = imu.accel()                                   # Read 3-axis acceleration, () → (float, float, float) m/s²
                                                           # converts raw counts at 16384 LSB/g (±2 g)
gx, gy, gz = imu.gyro()                                    # Read 3-axis angular rate, () → (float, float, float) rad/s
                                                           # converts raw counts at 128 LSB/dps (±256 dps)
rax, ray, raz = imu.accel_raw()                            # Read raw accel counts, () → (int, int, int)
                                                           # one 6-byte burst, signed 16-bit
rgx, rgy, rgz = imu.gyro_raw()                             # Read raw gyro counts, () → (int, int, int)
                                                           # one 6-byte burst, signed 16-bit
accel, gyro, temp = imu.read_all()                         # Read one coherent sample, () → ((float, float, float), (float, float, float), float) m/s², rad/s, °C
                                                           # one 14-byte burst of registers 0x33…0x40
t = imu.temperature()                                      # Read die temperature, () → float °C
                                                           # signed 8.8 fixed point, raw / 256
ts = imu.timestamp()                                       # Read sample counter, () → int
                                                           # 24-bit counter, wraps at 0xFFFFFF
ready = imu.data_ready()                                   # Check for new data, () → bool
                                                           # STATUS0 aDA / gDA

imu.configure_accel(full_scale=1, odr=5)                   # Configure accelerometer, (full_scale=0, odr=5) → None
                                                           # CTRL2: 0=±2, 1=±4, 2=±8, 3=±16 g; odr code 5 = 224.2 Hz
imu.configure_gyro(full_scale=5, odr=5)                    # Configure gyroscope, (full_scale=4, odr=5) → None
                                                           # CTRL3: 0=±16 … 7=±2048 dps
imu.configure_filters(True, 3, True, 3)                    # Configure low-pass filters, (accel_filter=True, accel_mode=3, gyro_filter=True, gyro_mode=3) → None
                                                           # CTRL5: mode 3 = 13.37 % of ODR
imu.set_sensors(True, True, False)                         # Enable sensors, (accel=True, gyro=True, gyro_snooze=False) → None
                                                           # CTRL7 aEN / gEN / gSN

imu.configure_fifo(2, 1, 16)                               # Configure FIFO, (mode=2, size=3, watermark=0) → None
                                                           # stream mode, 32 samples, watermark 16
time.sleep(0.5)
status = imu.fifo_status()                                 # Read FIFO flags, () → int
                                                           # raw FIFO_STATUS byte
count = imu.fifo_count()                                   # Read FIFO fill, () → int bytes
                                                           # two bytes per sample word
data = imu.read_fifo()                                     # Drain the FIFO, (count=None) → bytes
                                                           # REQ_FIFO, chunked FIFO_DATA reads, FIFO_RD_MODE cleared
imu.reset_fifo()                                           # Reset the FIFO, () → None
                                                           # CTRL9 RST_FIFO
imu.configure_fifo(0)                                      # Back to bypass, (mode=2, size=3, watermark=0) → None
                                                           # bypass re-enables data-ready

imu.configure_any_motion(0.25, 3, 0b111, False)            # Configure any-motion, (threshold_g=0.25, window=3, axes=0b111, logic_and=False) → None
                                                           # two CTRL9 CONFIGURE_MOTION sets
imu.configure_no_motion(0.125, 30, 0b111, True)            # Configure no-motion, (threshold_g=0.125, window=30, axes=0b111, logic_and=True) → None
                                                           # same command, no-motion fields
imu.configure_significant_motion(150, 600)                 # Configure significant motion, (wait_window=150, confirm_window=600) → None
                                                           # wait and confirm windows in samples
imu.enable_motion(True, True, True)                        # Enable motion engines, (any_motion=False, no_motion=False, significant=False) → None
                                                           # CTRL8 bits 1-3
imu.enable_motion()                                        # Disable motion engines, (any_motion=False, no_motion=False, significant=False) → None
                                                           # clears CTRL8 bits 1-3

imu.configure_tap()                                        # Configure tap engine, (priority=0, peak_window=20, tap_window=50, double_tap_window=250, alpha=0.0625, gamma=0.25, peak_threshold=0.8, quiet_threshold=0.4) → None
                                                           # two CTRL9 CONFIGURE_TAP sets
imu.enable_tap(True)                                       # Enable tap engine, (enabled=True) → None
                                                           # CTRL8.Tap_EN
taps, axis, negative = imu.tap_status()                    # Read tap event, () → (int, int, bool)
                                                           # count 0/1/2, axis 0-3, polarity
imu.enable_tap(False)                                      # Disable tap engine, (enabled=True) → None
                                                           # clears CTRL8.Tap_EN

imu.configure_pedometer()                                  # Configure pedometer, (sample_count=50, peak_to_peak_mg=200, peak_mg=100, time_up=200, time_low=20, entry_count=10, precision=0, report_every=4) → None
                                                           # two CTRL9 CONFIGURE_PEDOMETER sets (50 Hz values)
imu.enable_pedometer(True)                                 # Enable pedometer, (enabled=True) → None
                                                           # CTRL8.Pedo_EN; 0→1 resets the count
steps = imu.step_count()                                   # Read step count, () → int
                                                           # 24-bit count
imu.reset_step_count()                                     # Clear step count, () → None
                                                           # CTRL9 RESET_PEDOMETER
imu.enable_pedometer(False)                                # Disable pedometer, (enabled=True) → None
                                                           # clears CTRL8.Pedo_EN

imu.set_accel_offset(0.0, 0.0, 0.0)                        # Set accel offset, (x, y, z) m/s² → None
                                                           # signed 4.12 g via CTRL9
imu.set_gyro_offset(0.0, 0.0, 0.0)                         # Set gyro offset, (x, y, z) rad/s → None
                                                           # signed 11.5 dps via CTRL9
ok, cod_status = imu.calibrate_gyro()                      # Calibrate gyroscope, () → (bool, int)
                                                           # calibration-on-demand, ~1.5 s, keep still
gains = imu.get_gyro_gains()                               # Read gyro gains, () → (int, int, int)
                                                           # raw 16-bit gains from dVX…dVZ
imu.set_gyro_gains(*gains)                                 # Restore gyro gains, (x, y, z) → None
                                                           # CTRL9 APPLY_GYRO_GAINS
passed, values = imu.self_test_accel()                     # Run accel self-test, () → (bool, (float, float, float)) m/s²
                                                           # passes when every axis exceeds 200 mg
passed, values = imu.self_test_gyro()                      # Run gyro self-test, () → (bool, (float, float, float)) rad/s
                                                           # passes when every axis exceeds 300 dps
info = imu.device_info()                                   # Read device info, () → dict
                                                           # CTRL9 COPY_USID: revision, firmware, usid
imu.set_pullups(True, True, True, True)                    # Control pull-ups, (aux=True, sdx=True, cs=True, i2c=True) → None
                                                           # CTRL9 SET_RPU; False disables a group

imu.set_wake_on_motion(100, 1, 0, 0)                       # Arm wake-on-motion, (threshold_mg, interrupt_pin=1, initial_level=0, blanking_samples=0) → None
                                                           # accel 128 Hz ±2 g, CTRL9 WRITE_WOM_SETTING
imu.set_wake_on_motion(0)                                  # Leave wake-on-motion, (threshold_mg, interrupt_pin=1, initial_level=0, blanking_samples=0) → None
                                                           # threshold 0 exits WoM mode
imu.set_sensors(True, True)                                # Re-enable sensors, (accel=True, gyro=True, gyro_snooze=False) → None
                                                           # restores normal operation

imu.set_interrupt_pins(2, 2)                               # Route interrupts, (fifo_pin=2, activity_pin=2) → None
                                                           # CTRL1.FIFO_INT_SEL and CTRL8.ACTIVITY_INT_SEL
imu.enable_interrupt(QMI8658AFull.SOURCE_DATA_READY)       # Enable interrupt source, (source) → None
                                                           # clears DRDY_DIS, enables INT2
status = imu.poll_interrupt()                              # Poll interrupt status, () → int
                                                           # STATUS0, STATUS1 and FIFO_STATUS as SOURCE_* bits
imu.on_interrupt(lambda s: print('INT status 0x%04X' % s)) # Subscribe to interrupts, (callback) → None
                                                           # uses connection.int_pin or a polling thread
time.sleep(0.2)
imu.off_interrupt()                                        # Unsubscribe, () → None
                                                           # stops the callback
imu.disable_interrupt(QMI8658AFull.SOURCE_DATA_READY)      # Disable interrupt source, (source) → None
                                                           # sets DRDY_DIS, releases INT2

imu.set_sync_sample(True)                                  # Enable SyncSample, (enabled) → None
                                                           # locks each sample; reads wait for Avail and Locked
imu.set_sync_sample(False)                                 # Disable SyncSample, (enabled) → None
                                                           # restores the AHB clock gating
imu.set_power_down(True)                                   # Power down, (down=True) → None
                                                           # CTRL1.SensorDisable after disabling the sensors
imu.set_power_down(False)                                  # Wake, (down=True) → None
                                                           # clears SensorDisable
imu.set_sensors(True, True)                                # Re-enable sensors, (accel=True, gyro=True, gyro_snooze=False) → None
                                                           # needs 150 ms gyroscope turn-on
time.sleep(0.2)
imu.reset()                                                # Soft reset, () → None
                                                           # RESET register, waits 15 ms
