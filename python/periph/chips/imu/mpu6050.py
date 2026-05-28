import struct
import time


class MPU6050Minimal:
    """MPU-6050 6-axis MotionTracking device (accelerometer + gyroscope) — minimal interface.

    Provides 3-axis acceleration and 3-axis angular rate readings with no
    configuration beyond the transport. Performs device reset, WHO_AM_I check,
    and enables all sensors at defaults during initialization.

    Default configuration (baked in at construction):
        - Gyroscope full-scale: ±250 dps (FS_SEL=0)
        - Accelerometer full-scale: ±2 g (AFS_SEL=0)
        - DLPF: 44 Hz bandwidth (CONFIG DLPF_CFG=3, 1 kHz gyro rate)
        - Sample rate: 200 Hz (SMPLRT_DIV=4)
        - Clock: PLL with gyro X reference (CLKSEL=1)

    Args:
        transport: Configured I²C transport pointing at the device.
    """

    _REG_SMPLRT_DIV   = 0x19
    _REG_CONFIG       = 0x1A
    _REG_GYRO_CONFIG  = 0x1B
    _REG_ACCEL_CONFIG = 0x1C
    _REG_FIFO_EN      = 0x23
    _REG_INT_PIN_CFG  = 0x37
    _REG_INT_ENABLE   = 0x38
    _REG_INT_STATUS   = 0x3A
    _REG_ACCEL_XOUT_H = 0x3B
    _REG_TEMP_OUT_H   = 0x41
    _REG_GYRO_XOUT_H  = 0x43
    _REG_USER_CTRL    = 0x6A
    _REG_PWR_MGMT_1   = 0x6B
    _REG_PWR_MGMT_2   = 0x6C
    _REG_FIFO_COUNTH  = 0x72
    _REG_FIFO_COUNTL  = 0x73
    _REG_FIFO_R_W     = 0x74
    _REG_WHO_AM_I     = 0x75

    _WHO_AM_I_VALUE = 0x68

    _ACCEL_SENSITIVITY = (16384.0, 8192.0, 4096.0, 2048.0)
    _GYRO_SENSITIVITY  = (131.0, 65.5, 32.8, 16.4)

    def __init__(self, transport):
        self._transport = transport
        self._accel_fs = 0
        self._gyro_fs = 0
        self._write_reg(self._REG_PWR_MGMT_1, 0x80)
        time.sleep(0.1)
        self._write_reg(self._REG_PWR_MGMT_1, 0x01)
        who = self._read_reg(self._REG_WHO_AM_I)
        if who != self._WHO_AM_I_VALUE:
            raise ValueError('MPU6050 WHO_AM_I: expected 0x{:02X}, got 0x{:02X}'.format(
                self._WHO_AM_I_VALUE, who))
        self._write_reg(self._REG_GYRO_CONFIG, 0x00)
        self._write_reg(self._REG_ACCEL_CONFIG, 0x00)
        self._write_reg(self._REG_CONFIG, 0x03)
        self._write_reg(self._REG_SMPLRT_DIV, 0x04)
        time.sleep(0.035)

    def _write_reg(self, reg, value):
        self._transport.write(bytes([reg, value]))

    def _read_reg(self, reg):
        return self._transport.write_read(bytes([reg]), 1)[0]

    def _read_reg16_signed(self, reg):
        raw = self._transport.write_read(bytes([reg]), 2)
        return struct.unpack('>h', raw)[0]

    def _read_burst(self, reg, n):
        return self._transport.write_read(bytes([reg]), n)

    def accel(self):
        """Read 3-axis linear acceleration.

        Returns:
            tuple: (x, y, z) acceleration in m/s².
        """
        raw = self._read_burst(self._REG_ACCEL_XOUT_H, 6)
        ax, ay, az = struct.unpack('>hhh', raw)
        sens = self._ACCEL_SENSITIVITY[self._accel_fs]
        return (ax / sens * 9.80665, ay / sens * 9.80665, az / sens * 9.80665)

    def gyro(self):
        """Read 3-axis angular rate.

        Returns:
            tuple: (x, y, z) angular rate in rad/s.
        """
        raw = self._read_burst(self._REG_GYRO_XOUT_H, 6)
        gx, gy, gz = struct.unpack('>hhh', raw)
        sens = self._GYRO_SENSITIVITY[self._gyro_fs]
        return (gx / sens * 3.141592653589793 / 180.0,
                gy / sens * 3.141592653589793 / 180.0,
                gz / sens * 3.141592653589793 / 180.0)


class MPU6050Full(MPU6050Minimal):
    """MPU-6050 full interface — extends MPU6050Minimal with configuration and FIFO support.

    Adds gyroscope and accelerometer full-scale configuration, DLPF settings,
    sample rate control, temperature reading, raw data access, data-ready polling,
    sleep/standby control, and FIFO management.

    Args:
        transport: Configured I²C transport pointing at the device.
    """

    def __init__(self, transport):
        super().__init__(transport)

    def configure_gyro(self, full_scale=0):
        """Set gyroscope full-scale range.

        Args:
            full_scale: Range selector 0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
        """
        self._gyro_fs = full_scale & 0x03
        self._write_reg(self._REG_GYRO_CONFIG, (full_scale & 0x03) << 3)

    def configure_accel(self, full_scale=0):
        """Set accelerometer full-scale range.

        Args:
            full_scale: Range selector 0–3 (0=±2g, 1=±4g, 2=±8g, 3=±16g).
        """
        self._accel_fs = full_scale & 0x03
        self._write_reg(self._REG_ACCEL_CONFIG, (full_scale & 0x03) << 3)

    def configure_dlpf(self, dlpf=3):
        """Set digital low-pass filter bandwidth.

        Args:
            dlpf: Filter setting 0–6 (0=260/256 Hz, 1=184/188 Hz, 2=94/98 Hz,
                3=44/42 Hz, 4=21/20 Hz, 5=10/10 Hz, 6=5/5 Hz; gyro/accel BW).
        """
        self._write_reg(self._REG_CONFIG, dlpf & 0x07)

    def configure_sample_rate(self, divider=4):
        """Set sample rate divider.

        Args:
            divider: SMPLRT_DIV value 0–255; output rate = 1 kHz / (1 + divider)
                when DLPF is active.
        """
        self._write_reg(self._REG_SMPLRT_DIV, divider & 0xFF)

    def temperature(self):
        """Read die temperature.

        Returns:
            float: Temperature in °C.
        """
        raw = self._read_reg16_signed(self._REG_TEMP_OUT_H)
        return raw / 340.0 + 36.53

    def accel_raw(self):
        """Read raw 3-axis accelerometer values.

        Returns:
            tuple: (x, y, z) raw 16-bit signed values.
        """
        raw = self._read_burst(self._REG_ACCEL_XOUT_H, 6)
        return struct.unpack('>hhh', raw)

    def gyro_raw(self):
        """Read raw 3-axis gyroscope values.

        Returns:
            tuple: (x, y, z) raw 16-bit signed values.
        """
        raw = self._read_burst(self._REG_GYRO_XOUT_H, 6)
        return struct.unpack('>hhh', raw)

    def data_ready(self):
        """Check if new sensor data is available.

        Returns:
            bool: True when DATA_RDY_INT is set in INT_STATUS.
        """
        return bool(self._read_reg(self._REG_INT_STATUS) & 0x01)

    def set_sleep(self, sleep=True):
        """Set or clear the SLEEP bit in PWR_MGMT_1.

        Args:
            sleep: True to enter sleep mode, False to wake.
        """
        val = self._read_reg(self._REG_PWR_MGMT_1)
        if sleep:
            val |= 0x40
        else:
            val &= ~0x40
        self._write_reg(self._REG_PWR_MGMT_1, val)

    def set_standby(self, xa=False, ya=False, za=False, xg=False, yg=False, zg=False):
        """Put individual axes into standby mode.

        Args:
            xa: X accelerometer standby.
            ya: Y accelerometer standby.
            za: Z accelerometer standby.
            xg: X gyroscope standby.
            yg: Y gyroscope standby.
            zg: Z gyroscope standby.
        """
        val = ((xa & 1) << 5) | ((ya & 1) << 4) | ((za & 1) << 3) | \
              ((xg & 1) << 2) | ((yg & 1) << 1) | (zg & 1)
        self._write_reg(self._REG_PWR_MGMT_2, val)

    def fifo_count(self):
        """Read the number of bytes in the FIFO buffer.

        Returns:
            int: FIFO byte count (0–1024).
        """
        raw = self._read_burst(self._REG_FIFO_COUNTH, 2)
        return ((raw[0] & 0x1F) << 8) | raw[1]

    def read_fifo(self):
        """Read all available data from the FIFO buffer.

        Returns:
            bytes: FIFO data (length determined by fifo_count).
        """
        count = self.fifo_count()
        if count == 0:
            return b''
        return self._read_burst(self._REG_FIFO_R_W, count)

    def enable_fifo(self, gyro=True, accel=True, temp=False):
        """Configure and enable FIFO sources.

        Args:
            gyro: Enable gyroscope data in FIFO.
            accel: Enable accelerometer data in FIFO.
            temp: Enable temperature data in FIFO.
        """
        fifo_en = ((accel & 1) << 3) | ((temp & 1) << 2) | ((gyro & 1) << 4)
        self._write_reg(self._REG_FIFO_EN, fifo_en)
        user_ctrl = self._read_reg(self._REG_USER_CTRL)
        self._write_reg(self._REG_USER_CTRL, user_ctrl | 0x40)

    def reset_fifo(self):
        """Reset the FIFO buffer by setting FIFO_RST in USER_CTRL."""
        user_ctrl = self._read_reg(self._REG_USER_CTRL)
        self._write_reg(self._REG_USER_CTRL, user_ctrl | 0x04)
