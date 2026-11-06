from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.icm20948 import ICM20948Full
import time

connection = I2CConnection(0x68)
imu = ICM20948Full(connection)                            # Create ICM20948 driver, (connection) → None

ax, ay, az = imu.accel()                                  # Read 3-axis acceleration, () → (float, float, float) m/s²
                                                          # converts raw accel registers to m/s² (16384 LSB/g at ±2g)
gx, gy, gz = imu.gyro()                                   # Read 3-axis angular rate, () → (float, float, float) rad/s
                                                          # converts raw gyro registers to rad/s (131 LSB/(°/s) at ±250dps)
accel, gyro, temp = imu.read_all()                        # Read one coherent sample, () → ((float, float, float), (float, float, float), float) m/s², rad/s, °C
                                                          # burst-reads registers 0x2D…0x3A so all three belong to one sample
t = imu.temperature()                                     # Read die temperature, () → float °C
                                                          # converts raw temp register: raw/333.87 + 21.0

rax, ray, raz = imu.accel_raw()                           # Read raw accel values, () → (int, int, int)
                                                          # returns signed 16-bit accelerometer counts
rgx, rgy, rgz = imu.gyro_raw()                            # Read raw gyro values, () → (int, int, int)
                                                          # returns signed 16-bit gyroscope counts

imu.configure_gyro(full_scale=1)                          # Configure gyro range, (full_scale=0) → None
                                                          # sets GYRO_FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
imu.configure_accel(full_scale=1)                         # Configure accel range, (full_scale=0) → None
                                                          # sets ACCEL_FS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
imu.configure_filters(gyro_dlpf=3, accel_dlpf=3, gyro_filter=True, accel_filter=True)  # Configure low-pass filters, (gyro_dlpf=3, accel_dlpf=3, gyro_filter=True, accel_filter=True) → None
                                                          # sets *_DLPFCFG and *_FCHOICE; filter False bypasses the filter
imu.configure_sample_rate(gyro_div=10, accel_div=10)      # Configure sample rate, (gyro_div=10, accel_div=10) → None
                                                          # sets the dividers; ODR = 1125 Hz / (1 + div)
imu.set_sensors(accel=True, gyro=True)                    # Power sensors, (accel=True, gyro=True) → None
                                                          # writes PWR_MGMT_2 DISABLE_ACCEL / DISABLE_GYRO
imu.set_low_power(accel=False, gyro=False, accel_avg=0, gyro_avg=0)  # Configure duty-cycled mode, (accel=False, gyro=False, accel_avg=0, gyro_avg=0) → None
                                                          # writes LP_CONFIG and averaging; LP_EN is set last
imu.set_gyro_offset(0.0, 0.0, 0.0)                        # Set gyro offsets, (x, y, z) rad/s → None
                                                          # writes the 16-bit offset-cancellation registers (0.0305 dps/LSB)
ox, oy, oz = imu.get_accel_offset()                       # Read accel offset codes, () → (int, int, int)
                                                          # reads the factory-trimmed 15-bit offsets (0.98 mg/LSB)
imu.set_accel_offset(ox, oy, oz)                          # Write accel offset codes, (x, y, z) → None
                                                          # writes the 15-bit offsets back unchanged

imu.enable_mag()                                          # Enable magnetometer, () → None
                                                          # starts the I²C master and reads the AK09916 block continuously
mx, my, mz = imu.mag()                                    # Read 3-axis magnetic field, () → (float, float, float) µT
                                                          # converts raw counts at 0.15 µT/LSB; raises on HOFL
rmx, rmy, rmz = imu.mag_raw()                             # Read raw mag values, () → (int, int, int)
                                                          # returns signed 16-bit magnetometer counts
mag_ready = imu.mag_data_ready()                          # Check magnetometer data ready, () → bool
                                                          # reads ST1.DRDY from the shadowed block
imu.mag_set_mode(8)                                       # Set magnetometer mode, (mode) → None
                                                          # writes CNTL2: 0=off, 1=single, 2/4/6/8=continuous 1-4
who = imu.aux_read_byte(0x0C, 0x01)                       # Read auxiliary byte, (address, register) → int
                                                          # reads AK09916 WIA2 (0x09) through I²C-master slave 4
imu.aux_write_byte(0x0C, 0x31, 0x08)                      # Write auxiliary byte, (address, register, value) → None
                                                          # writes AK09916 CNTL2 through slave 4

ready = imu.data_ready()                                  # Check data ready flag, () → bool
                                                          # reads INT_STATUS_1.RAW_DATA_0_RDY_INT

imu.enable_fifo(accel=True, gyro=True, temp=False)        # Enable FIFO sources, (accel=True, gyro=True, temp=False) → None
                                                          # sets FIFO_EN_2 and USER_CTRL.FIFO_EN, then resets the FIFO
imu.set_fifo_mode(snapshot=False)                         # Select FIFO mode, (snapshot=False) → None
                                                          # 0 = stream (overwrite oldest), 1 = snapshot (stop when full)
time.sleep_ms(50)
count = imu.fifo_count()                                  # Read FIFO byte count, () → int
                                                          # reads FIFO_COUNTH/L: number of bytes available
data = imu.read_fifo()                                    # Read FIFO data, (count=None) → bytes
                                                          # reads all available bytes from FIFO_R_W in chunks
imu.reset_fifo()                                          # Reset FIFO buffer, () → None
                                                          # asserts and de-asserts FIFO_RST

imu.set_wake_on_motion(threshold_mg=200, enabled=True)    # Arm wake-on-motion, (threshold_mg, enabled=True) → None
                                                          # writes ACCEL_WOM_THR (4 mg/LSB) and ACCEL_INTEL_CTRL
imu.set_interrupt_pin(active_low=False, open_drain=False, latch=True, any_read_clears=False)  # Configure INT1 pin, (active_low=False, open_drain=False, latch=False, any_read_clears=False) → None
                                                          # writes INT_PIN_CFG
imu.enable_interrupt(ICM20948Full.SOURCE_DATA_READY)      # Enable interrupt source, (source) → None
                                                          # sets the source's enable bit in INT_ENABLE*
status = imu.poll_interrupt()                             # Read interrupt status, () → int
                                                          # reads INT_STATUS, _1, _2 (clearing them) into SOURCE_* bits
imu.disable_interrupt(ICM20948Full.SOURCE_DATA_READY)     # Disable interrupt source, (source) → None
                                                          # clears the source's enable bit

imu.on_interrupt(lambda s: print('INT', s))               # Subscribe to INT1, (callback) → None
                                                          # callback receives the status int from poll_interrupt
imu.off_interrupt()                                       # Unsubscribe, () → None
                                                          # removes the callback

resp = imu.self_test()                                    # Run self-test, () → ((float, float, float), (float, float, float))
                                                          # returns the raw response (enabled minus disabled) per axis; no pass/fail
tbc = imu.read_timebase_correction()                      # Read PLL time-base correction, () → float %
                                                          # reads TIMEBASE_CORRECTION_PLL (0.079 %/LSB, signed)

imu.set_sleep(sleep=True)                                 # Enter sleep mode, (sleep=True) → None
                                                          # sets SLEEP bit in PWR_MGMT_1
time.sleep_ms(10)
imu.set_sleep(sleep=False)                                # Wake from sleep, (sleep=True) → None
                                                          # clears SLEEP bit in PWR_MGMT_1
imu.reset()                                               # Reset the device, () → None
                                                          # DEVICE_RESET and wait; create a new driver afterwards
