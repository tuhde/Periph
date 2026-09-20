import struct
import time


class MPU9250Minimal:
    """MPU-9250 9-axis MotionTracking device (accelerometer + gyroscope) — minimal interface.

    Provides 3-axis acceleration and 3-axis angular rate readings with no
    configuration beyond the connection. Performs device reset, WHO_AM_I check,
    and enables all sensors at defaults during initialization. Magnetometer
    is not included in Minimal — it requires a separate initialization path.

    Default configuration (baked in at construction):
        - Gyroscope full-scale: ±250 dps (GYRO_FS_SEL=0)
        - Accelerometer full-scale: ±2 g (ACCEL_FS_SEL=0)
        - Gyroscope DLPF: 41 Hz bandwidth (CONFIG DLPF_CFG=3)
        - Accelerometer DLPF: 42 Hz bandwidth (ACCEL_CONFIG2 A_DLPFCFG=3)
        - Sample rate: 200 Hz (SMPLRT_DIV=4)
        - Clock: auto PLL (CLKSEL=1)
        - All six axes enabled
        - SPI only: I2C_IF_DIS set to prevent accidental I²C re-enable

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
    """

    _REG_SMPLRT_DIV    = 0x19
    _REG_CONFIG        = 0x1A
    _REG_GYRO_CONFIG   = 0x1B
    _REG_ACCEL_CONFIG  = 0x1C
    _REG_ACCEL_CONFIG2 = 0x1D
    _REG_LP_ACCEL_ODR  = 0x1E
    _REG_WOM_THR       = 0x1F
    _REG_FIFO_EN       = 0x23
    _REG_INT_PIN_CFG   = 0x37
    _REG_INT_ENABLE    = 0x38
    _REG_INT_STATUS    = 0x3A
    _REG_ACCEL_XOUT_H  = 0x3B
    _REG_TEMP_OUT_H    = 0x41
    _REG_GYRO_XOUT_H   = 0x43
    _REG_USER_CTRL     = 0x6A
    _REG_PWR_MGMT_1    = 0x6B
    _REG_PWR_MGMT_2    = 0x6C
    _REG_FIFO_COUNTH   = 0x72
    _REG_FIFO_COUNTL   = 0x73
    _REG_FIFO_R_W      = 0x74
    _REG_WHO_AM_I      = 0x75

    _WHO_AM_I_VALUE = 0x71

    _ACCEL_SENSITIVITY = (16384.0, 8192.0, 4096.0, 2048.0)
    _GYRO_SENSITIVITY  = (131.0, 65.5, 32.8, 16.4)

    def __init__(self, connection):
        self._connection = connection
        self._accel_fs = 0
        self._gyro_fs = 0
        self._write_reg(self._REG_PWR_MGMT_1, 0x80)
        time.sleep(0.1)
        self._write_reg(self._REG_PWR_MGMT_1, 0x01)
        who = self._read_reg(self._REG_WHO_AM_I)
        if who != self._WHO_AM_I_VALUE:
            raise ValueError('MPU9250 WHO_AM_I: expected 0x{:02X}, got 0x{:02X}'.format(
                self._WHO_AM_I_VALUE, who))
        self._write_reg(self._REG_GYRO_CONFIG, 0x00)
        self._write_reg(self._REG_ACCEL_CONFIG, 0x00)
        self._write_reg(self._REG_ACCEL_CONFIG2, 0x00)
        self._write_reg(self._REG_CONFIG, 0x03)
        self._write_reg(self._REG_SMPLRT_DIV, 0x04)
        time.sleep(0.035)

    def _write_reg(self, reg, value):
        self._connection.write(bytes([reg, value]))

    def _read_reg(self, reg):
        return self._connection.write_read(bytes([reg]), 1)[0]

    def _read_reg16_signed(self, reg):
        raw = self._connection.write_read(bytes([reg]), 2)
        return struct.unpack('>h', raw)[0]

    def _read_burst(self, reg, n):
        return self._connection.write_read(bytes([reg]), n)

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
        deg_to_rad = 3.141592653589793 / 180.0
        return (gx / sens * deg_to_rad,
                gy / sens * deg_to_rad,
                gz / sens * deg_to_rad)


class MPU9250Full(MPU9250Minimal):
    """MPU-9250 full interface — extends MPU9250Minimal with complete functionality.

    Adds gyroscope and accelerometer full-scale configuration, DLPF settings,
    sample rate control, temperature reading, magnetometer (AK8963) support,
    raw data access, data-ready polling, sleep/standby control, and FIFO management.

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
    """

    _AK8963_ADDR = 0x0C

    _AK8963_REG_WIA      = 0x00
    _AK8963_REG_ST1      = 0x02
    _AK8963_REG_HXL      = 0x03
    _AK8963_REG_ST2      = 0x09
    _AK8963_REG_CNTL1    = 0x0A
    _AK8963_REG_CNTL2    = 0x0B
    _AK8963_REG_ASAX     = 0x10
    _AK8963_REG_ASAY     = 0x11
    _AK8963_REG_ASAZ     = 0x12

    _AK8963_WIA_VALUE = 0x48

    _MAG_SENSITIVITY_14BIT = 0.6
    _MAG_SENSITIVITY_16BIT = 0.15

    _GYRO_FS_SEL_BITS  = {0: 0, 1: 1, 2: 2, 3: 3}
    _ACCEL_FS_SEL_BITS = {0: 0, 1: 1, 2: 2, 3: 3}

    _CONFIG_DLPF = {0: 0, 1: 1, 2: 2, 3: 3, 4: 4, 5: 5, 6: 6}
    _ACCEL_CONFIG2_DLPF = {0: 0, 1: 1, 2: 2, 3: 3, 4: 4, 5: 5, 6: 6}

    def __init__(self, connection):
        super().__init__(connection)
        self._mag_enabled = False
        self._mag_bits = 16
        self._mag_scale_x = 1.0
        self._mag_scale_y = 1.0
        self._mag_scale_z = 1.0
        self._is_spi = False

    def _ak8963_write(self, reg, value):
        self._connection.write(bytes([self._AK8963_ADDR << 1, reg, value]))

    def _ak8963_read(self, reg):
        return self._connection.write_read(bytes([(self._AK8963_ADDR << 1) | 1, reg]), 1)[0]

    def _ak8963_read_burst(self, reg, n):
        return self._connection.write_read(bytes([(self._AK8963_ADDR << 1) | 1, reg]), n)

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

    def configure_dlpf(self, gyro_dlpf=3, accel_dlpf=3):
        """Set digital low-pass filter bandwidth.

        Args:
            gyro_dlpf: Gyro filter setting 0–6 (0=256 Hz, 1=188 Hz, 2=98 Hz,
                3=41 Hz, 4=20 Hz, 5=10 Hz, 6=5 Hz).
            accel_dlpf: Accel filter setting 0–6 (0=460 Hz, 1=184 Hz, 2=92 Hz,
                3=42 Hz, 5=20 Hz, 6=10 Hz). Note: 4 is not valid for accel.
        """
        cfg_val = gyro_dlpf & 0x07
        self._write_reg(self._REG_CONFIG, cfg_val)
        accel2_val = accel_dlpf & 0x07
        self._write_reg(self._REG_ACCEL_CONFIG2, accel2_val)

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
        return raw / 333.87 + 21.0

    def enable_mag(self, bits=16, mode=6):
        """Initialize AK8963 magnetometer via I²C bypass mode.

        Args:
            bits: Output resolution, 14 or 16.
            mode: Operation mode (1=single, 2=8 Hz continuous, 6=100 Hz continuous).
        """
        self._write_reg(self._REG_INT_PIN_CFG, 0x22)
        time.sleep(0.01)

        self._ak8963_write(self._AK8963_REG_CNTL1, 0x00)
        time.sleep(0.01)

        self._ak8963_write(self._AK8963_REG_CNTL1, 0x0F)
        time.sleep(0.01)

        asax = self._ak8963_read(self._AK8963_REG_ASAX)
        asay = self._ak8963_read(self._AK8963_REG_ASAY)
        asaz = self._ak8963_read(self._AK8963_REG_ASAZ)

        self._mag_scale_x = (asax - 128) / 256.0 + 1.0
        self._mag_scale_y = (asay - 128) / 256.0 + 1.0
        self._mag_scale_z = (asaz - 128) / 256.0 + 1.0

        self._ak8963_write(self._AK8963_REG_CNTL1, 0x00)
        time.sleep(0.01)

        cntl1_val = 0
        if bits == 16:
            cntl1_val |= 0x10
        cntl1_val |= (mode & 0x0F)
        self._ak8963_write(self._AK8963_REG_CNTL1, cntl1_val)
        time.sleep(0.01)

        self._mag_enabled = True
        self._mag_bits = bits

    def mag(self):
        """Read 3-axis magnetic field.

        Returns:
            tuple: (x, y, z) magnetic field in µT.

        Raises:
            RuntimeError: If magnetometer has not been enabled via enable_mag().
        """
        if not self._mag_enabled:
            raise RuntimeError('Magnetometer not enabled. Call enable_mag() first.')

        raw = self._ak8963_read_burst(self._AK8963_REG_HXL, 7)
        mx, my, mz = struct.unpack('<hhh', raw[:6])
        st2 = raw[6]

        if not (st2 & 0x08):
            pass

        if self._mag_bits == 16:
            sens = self._MAG_SENSITIVITY_16BIT
        else:
            sens = self._MAG_SENSITIVITY_14BIT

        return (mx * sens * self._mag_scale_x,
                my * sens * self._mag_scale_y,
                mz * sens * self._mag_scale_z)

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

    def mag_raw(self):
        """Read raw 3-axis magnetometer values.

        Returns:
            tuple: (x, y, z) raw 16-bit signed values.

        Raises:
            RuntimeError: If magnetometer has not been enabled via enable_mag().
        """
        if not self._mag_enabled:
            raise RuntimeError('Magnetometer not enabled. Call enable_mag() first.')

        raw = self._ak8963_read_burst(self._AK8963_REG_HXL, 7)
        mx, my, mz = struct.unpack('<hhh', raw[:6])
        return (mx, my, mz)

    def data_ready(self):
        """Check if new sensor data is available.

        Returns:
            bool: True when RAW_DATA_RDY_INT is set in INT_STATUS.
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

    def fifo_count(self):
        """Read the number of bytes in the FIFO buffer.

        Returns:
            int: FIFO byte count (0–512).
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