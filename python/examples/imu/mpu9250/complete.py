from periph.connection.i2c_auto import I2CConnection
from periph.chips.imu.mpu9250 import MPU9250Full
import time

connection = I2CConnection(0x68)
imu = MPU9250Full(connection)                             # Create MPU9250 driver, (connection) → None

ax, ay, az = imu.accel()                                  # Read 3-axis acceleration, () → (float, float, float) m/s²
                                                          # converts raw accel register to m/s² (16384 LSB/g at ±2g)
gx, gy, gz = imu.gyro()                                   # Read 3-axis angular rate, () → (float, float, float) rad/s
                                                          # converts raw gyro register to rad/s (131.0 LSB/(°/s) at ±250dps)

imu.configure_gyro(full_scale=1)                          # Configure gyro range, (full_scale=0) → None
                                                          # sets GYRO_FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
imu.configure_accel(full_scale=1)                         # Configure accel range, (full_scale=0) → None
                                                          # sets ACCEL_FS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
imu.configure_dlpf(gyro_dlpf=3, accel_dlpf=3)             # Configure DLPF bandwidth, (gyro_dlpf=3, accel_dlpf=3) → None
                                                          # sets DLPF_CFG/A_DLPFCFG: 0=256/460Hz … 6=5/10Hz (gyro/accel BW)
imu.configure_sample_rate(divider=4)                      # Configure sample rate, (divider=4) → None
                                                          # sets SMPLRT_DIV: output rate = 1kHz / (1 + divider)

t = imu.temperature()                                     # Read die temperature, () → float °C
                                                          # converts raw temp register: raw/333.87 + 21.0

imu.enable_mag(bits=16, mode=6)                           # Initialize magnetometer, (bits=16, mode=6) → None
                                                          # reads ASA calibration, sets 16-bit 100 Hz continuous mode
mx, my, mz = imu.mag()                                    # Read 3-axis magnetic field, () → (float, float, float) µT
                                                          # applies factory ASA calibration and sensitivity scaling

rax, ray, raz = imu.accel_raw()                           # Read raw accel values, () → (int, int, int)
                                                          # returns raw 16-bit signed accelerometer register values
rgx, rgy, rgz = imu.gyro_raw()                            # Read raw gyro values, () → (int, int, int)
                                                          # returns raw 16-bit signed gyroscope register values
rmx, rmy, rmz = imu.mag_raw()                             # Read raw mag values, () → (int, int, int)
                                                          # returns raw 16-bit signed magnetometer register values

ready = imu.data_ready()                                  # Check data ready flag, () → bool
                                                          # reads RAW_DATA_RDY_INT bit from INT_STATUS register

imu.set_sleep(sleep=True)                                 # Enter sleep mode, (sleep=True) → None
                                                          # sets SLEEP bit in PWR_MGMT_1
time.sleep_ms(10)
imu.set_sleep(sleep=False)                                # Wake from sleep, (sleep=True) → None
                                                          # clears SLEEP bit in PWR_MGMT_1

imu.reset_fifo()                                          # Reset FIFO buffer, () → None
                                                          # sets FIFO_RST bit in USER_CTRL to clear the buffer
imu.enable_fifo(gyro=True, accel=True)                    # Enable FIFO sources, (gyro=True, accel=True, temp=False) → None
                                                          # configures FIFO_EN and sets FIFO_EN bit in USER_CTRL
time.sleep_ms(50)
count = imu.fifo_count()                                  # Read FIFO byte count, () → int
                                                          # reads FIFO_COUNTH/L: number of bytes available
data = imu.read_fifo()                                    # Read FIFO data, () → bytes
                                                          # reads all available bytes from FIFO_R_W register
imu.reset_fifo()                                          # Reset FIFO buffer, () → None
                                                          # sets FIFO_RST bit in USER_CTRL to clear the buffer