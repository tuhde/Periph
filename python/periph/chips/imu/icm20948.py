"""ICM-20948 — 9-axis MotionTracking device (TDK InvenSense).

3-axis gyroscope, 3-axis accelerometer and an AK09916 3-axis magnetometer
in one package, with a 4 KiB FIFO, wake-on-motion interrupt, programmable
low-pass filters and sample-rate dividers, low-power duty-cycled modes and
an auxiliary I²C master that reads the magnetometer without host
involvement.

Communicates over I²C at address 0x68 (AD0 = GND) or 0x69 (AD0 = VDDIO).
VDDIO is 1.71-1.95 V only. The registers are organised in four *user
banks* selected through ``REG_BANK_SEL`` (0x7F); the driver caches the
selected bank and always returns to bank 0 after a bank 1-3 access. SPI and
the DMP are out of scope.
"""

import struct
import time

from periph.connection.register import to_signed

_G = 9.80665
_DEG_TO_RAD = 3.141592653589793 / 180.0


class ICM20948Minimal:
    """ICM-20948 minimal interface — acceleration and angular rate.

    Provides 3-axis acceleration and 3-axis angular rate with no
    configuration beyond the connection. The magnetometer is not part of
    Minimal — it needs the auxiliary I²C master (see :class:`ICM20948Full`).

    Default configuration (baked in at construction):
        - Gyroscope full-scale: ±250 dps, low-pass filter 51.2 Hz (DLPFCFG=3)
        - Accelerometer full-scale: ±2 g, low-pass filter 50.4 Hz (DLPFCFG=3)
        - Both output data rates 102.3 Hz (divider 10)
        - Clock: auto (CLKSEL=1); all six axes and the temperature sensor on
        - FIFO, DMP, I²C master and all interrupts off

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.

    Raises:
        ValueError: If WHO_AM_I does not read 0xEA.
    """

    _REG_WHO_AM_I     = 0x00
    _REG_USER_CTRL    = 0x03
    _REG_LP_CONFIG    = 0x05
    _REG_PWR_MGMT_1   = 0x06
    _REG_PWR_MGMT_2   = 0x07
    _REG_INT_PIN_CFG  = 0x0F
    _REG_INT_ENABLE   = 0x10
    _REG_INT_ENABLE_1 = 0x11
    _REG_INT_ENABLE_2 = 0x12
    _REG_I2C_MST_STATUS = 0x17
    _REG_INT_STATUS   = 0x19
    _REG_INT_STATUS_1 = 0x1A
    _REG_ACCEL_XOUT_H = 0x2D
    _REG_GYRO_XOUT_H  = 0x33
    _REG_TEMP_OUT_H   = 0x39
    _REG_EXT_SLV_SENS_DATA_00 = 0x3B
    _REG_FIFO_EN_2    = 0x67
    _REG_FIFO_RST     = 0x68
    _REG_FIFO_MODE    = 0x69
    _REG_FIFO_COUNTH  = 0x70
    _REG_FIFO_R_W     = 0x72
    _REG_BANK_SEL     = 0x7F

    # Bank 1
    _REG_XA_OFFS_H    = 0x14
    _REG_TIMEBASE_CORRECTION_PLL = 0x28

    # Bank 2
    _REG_GYRO_SMPLRT_DIV   = 0x00
    _REG_GYRO_CONFIG_1     = 0x01
    _REG_GYRO_CONFIG_2     = 0x02
    _REG_XG_OFFS_USRH      = 0x03
    _REG_ACCEL_SMPLRT_DIV_1 = 0x10
    _REG_ACCEL_SMPLRT_DIV_2 = 0x11
    _REG_ACCEL_INTEL_CTRL  = 0x12
    _REG_ACCEL_WOM_THR     = 0x13
    _REG_ACCEL_CONFIG      = 0x14
    _REG_ACCEL_CONFIG_2    = 0x15

    # Bank 3
    _REG_I2C_MST_CTRL = 0x01
    _REG_I2C_SLV0_ADDR = 0x03
    _REG_I2C_SLV0_REG  = 0x04
    _REG_I2C_SLV0_CTRL = 0x05
    _REG_I2C_SLV4_ADDR = 0x13
    _REG_I2C_SLV4_REG  = 0x14
    _REG_I2C_SLV4_CTRL = 0x15
    _REG_I2C_SLV4_DO   = 0x16
    _REG_I2C_SLV4_DI   = 0x17

    _WHO_AM_I_VALUE = 0xEA

    _ACCEL_SENSITIVITY = (16384.0, 8192.0, 4096.0, 2048.0)
    _GYRO_SENSITIVITY  = (131.0, 65.5, 32.8, 16.4)

    def __init__(self, connection):
        self._connection = connection
        self._bank = 0
        self._accel_fs = 0
        self._gyro_fs = 0
        self._accel_dlpf = 3
        self._gyro_dlpf = 3
        self._accel_filter = True
        self._gyro_filter = True
        self._lp_en = False
        self._lp_suspended = False

        self._select_bank(0, force=True)
        who = self._connection.read_reg(self._REG_WHO_AM_I, 1)[0]
        if who != self._WHO_AM_I_VALUE:
            raise ValueError('ICM20948 WHO_AM_I: expected 0x{:02X}, got 0x{:02X}'.format(
                self._WHO_AM_I_VALUE, who))
        self._connection.write_reg(self._REG_PWR_MGMT_1, 0x80)  # DEVICE_RESET
        time.sleep(0.1)
        self._bank = 0                                          # reset returns to bank 0
        self._connection.write_reg(self._REG_PWR_MGMT_1, 0x01)  # SLEEP=0, CLKSEL=1
        self._connection.write_reg(self._REG_PWR_MGMT_2, 0x00)  # all axes on
        self._write_gyro_config()
        self._write_accel_config()
        self._wr(2, self._REG_GYRO_SMPLRT_DIV, 0x0A)
        self._wr(2, self._REG_ACCEL_SMPLRT_DIV_1, 0x00)
        self._wr(2, self._REG_ACCEL_SMPLRT_DIV_2, 0x0A)
        time.sleep(0.035)

    # -- bank handling ---------------------------------------------------

    def _select_bank(self, bank, force=False):
        if force or bank != self._bank:
            self._connection.write_reg(self._REG_BANK_SEL, bank << 4)
            self._bank = bank

    def _rd(self, bank, reg, length):
        self._select_bank(bank)
        data = self._connection.read_reg(reg, length)
        if bank != 0:
            self._select_bank(0)
        return data

    def _wr(self, bank, reg, data):
        self._select_bank(bank)
        self._connection.write_reg(reg, data)
        if bank != 0:
            self._select_bank(0)

    def _write_gyro_config(self):
        val = (self._gyro_dlpf << 3) | (self._gyro_fs << 1) | (1 if self._gyro_filter else 0)
        self._wr(2, self._REG_GYRO_CONFIG_1, val)

    def _write_accel_config(self):
        val = (self._accel_dlpf << 3) | (self._accel_fs << 1) | (1 if self._accel_filter else 0)
        self._wr(2, self._REG_ACCEL_CONFIG, val)

    # -- measurements ----------------------------------------------------

    def accel(self):
        """Read 3-axis linear acceleration.

        Returns:
            tuple: (x, y, z) acceleration in m/s².
        """
        raw = self._rd(0, self._REG_ACCEL_XOUT_H, 6)
        ax, ay, az = struct.unpack('>hhh', raw)
        sens = self._ACCEL_SENSITIVITY[self._accel_fs]
        return (ax / sens * _G, ay / sens * _G, az / sens * _G)

    def gyro(self):
        """Read 3-axis angular rate.

        Returns:
            tuple: (x, y, z) angular rate in rad/s.
        """
        raw = self._rd(0, self._REG_GYRO_XOUT_H, 6)
        gx, gy, gz = struct.unpack('>hhh', raw)
        sens = self._GYRO_SENSITIVITY[self._gyro_fs]
        return (gx / sens * _DEG_TO_RAD,
                gy / sens * _DEG_TO_RAD,
                gz / sens * _DEG_TO_RAD)


class ICM20948Full(ICM20948Minimal):
    """ICM-20948 full interface — extends :class:`ICM20948Minimal`.

    Adds raw and burst reads, temperature, full-scale / filter / sample-rate
    configuration, sensor power control, low-power modes, gyro and
    accelerometer offsets, the AK09916 magnetometer (read through the
    chip's own I²C master — no second connection is needed), single-byte
    auxiliary-bus access, the 4 KiB FIFO, wake-on-motion, interrupts,
    self-test and the PLL time-base correction.

    Methods that touch banks 1-3 select the bank and return to bank 0, so
    the bank register is 0 between calls.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
    """

    SOURCE_DATA_READY    = 0x01
    SOURCE_WAKE_ON_MOTION = 0x02
    SOURCE_I2C_MASTER    = 0x04
    SOURCE_PLL_READY     = 0x08
    SOURCE_FIFO_OVERFLOW = 0x10

    _AK_ADDR = 0x0C
    _AK_WIA2 = 0x01
    _AK_ST1  = 0x10
    _AK_CNTL2 = 0x31
    _AK_CNTL3 = 0x32
    _AK_WIA2_VALUE = 0x09
    _MAG_SENSITIVITY = 0.15

    # source -> (enable register, mask)
    _INT_ENABLE_MAP = {
        0x01: (0x11, 0x01),
        0x02: (0x10, 0x08),
        0x04: (0x10, 0x01),
        0x08: (0x10, 0x04),
        0x10: (0x12, 0x1F),
    }

    def __init__(self, connection):
        super().__init__(connection)
        self._mag_enabled = False
        self._int_callback = None

    # -- low-power guard -------------------------------------------------

    def _lp_suspend(self):
        if self._lp_en and not self._lp_suspended:
            val = self._connection.read_reg(self._REG_PWR_MGMT_1, 1)[0]
            self._connection.write_reg(self._REG_PWR_MGMT_1, val & ~0x20)
            self._lp_suspended = True

    def _lp_resume(self):
        if self._lp_suspended:
            val = self._connection.read_reg(self._REG_PWR_MGMT_1, 1)[0]
            self._connection.write_reg(self._REG_PWR_MGMT_1, val | 0x20)
            self._lp_suspended = False

    # -- raw data --------------------------------------------------------

    def accel_raw(self):
        """Read raw 3-axis accelerometer values.

        Returns:
            tuple: (x, y, z) signed 16-bit counts.
        """
        return struct.unpack('>hhh', self._rd(0, self._REG_ACCEL_XOUT_H, 6))

    def gyro_raw(self):
        """Read raw 3-axis gyroscope values.

        Returns:
            tuple: (x, y, z) signed 16-bit counts.
        """
        return struct.unpack('>hhh', self._rd(0, self._REG_GYRO_XOUT_H, 6))

    def read_all(self):
        """Burst-read acceleration, angular rate and temperature in one sample.

        Returns:
            tuple: ``((ax, ay, az), (gx, gy, gz), temp_c)`` — m/s², rad/s, °C.
        """
        raw = self._rd(0, self._REG_ACCEL_XOUT_H, 14)
        v = struct.unpack('>hhhhhhh', raw)
        a_sens = self._ACCEL_SENSITIVITY[self._accel_fs]
        g_sens = self._GYRO_SENSITIVITY[self._gyro_fs]
        accel = tuple(x / a_sens * _G for x in v[0:3])
        gyro = tuple(x / g_sens * _DEG_TO_RAD for x in v[3:6])
        return (accel, gyro, v[6] / 333.87 + 21.0)

    def temperature(self):
        """Read die temperature.

        Returns:
            float: Temperature in °C.
        """
        raw = struct.unpack('>h', self._rd(0, self._REG_TEMP_OUT_H, 2))[0]
        return raw / 333.87 + 21.0

    # -- configuration ---------------------------------------------------

    def configure_gyro(self, full_scale=0):
        """Set gyroscope full-scale range.

        Args:
            full_scale: 0-3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
        """
        self._lp_suspend()
        self._gyro_fs = full_scale & 0x03
        self._write_gyro_config()
        self._lp_resume()

    def configure_accel(self, full_scale=0):
        """Set accelerometer full-scale range.

        Args:
            full_scale: 0-3 (0=±2, 1=±4, 2=±8, 3=±16 g).
        """
        self._lp_suspend()
        self._accel_fs = full_scale & 0x03
        self._write_accel_config()
        self._lp_resume()

    def configure_filters(self, gyro_dlpf=3, accel_dlpf=3, gyro_filter=True, accel_filter=True):
        """Set the digital low-pass filters.

        Args:
            gyro_dlpf: GYRO_DLPFCFG 0-7 (3 = 51.2 Hz).
            accel_dlpf: ACCEL_DLPFCFG 0-7 (3 = 50.4 Hz).
            gyro_filter: False bypasses the gyro filter (9 kHz output rate).
            accel_filter: False bypasses the accelerometer filter (4.5 kHz).
        """
        self._lp_suspend()
        self._gyro_dlpf = gyro_dlpf & 0x07
        self._accel_dlpf = accel_dlpf & 0x07
        self._gyro_filter = bool(gyro_filter)
        self._accel_filter = bool(accel_filter)
        self._write_gyro_config()
        self._write_accel_config()
        self._lp_resume()

    def configure_sample_rate(self, gyro_div=10, accel_div=10):
        """Set the sample-rate dividers; ODR = 1125 Hz / (1 + div).

        Args:
            gyro_div: GYRO_SMPLRT_DIV 0-255.
            accel_div: ACCEL_SMPLRT_DIV 0-4095.
        """
        self._lp_suspend()
        self._wr(2, self._REG_GYRO_SMPLRT_DIV, gyro_div & 0xFF)
        self._wr(2, self._REG_ACCEL_SMPLRT_DIV_1, (accel_div >> 8) & 0x0F)
        self._wr(2, self._REG_ACCEL_SMPLRT_DIV_2, accel_div & 0xFF)
        self._lp_resume()

    def set_sensors(self, accel=True, gyro=True):
        """Switch the accelerometer and gyroscope on or off (PWR_MGMT_2).

        Turning a sensor on again needs its start-up time (accel 20 ms,
        gyro 35 ms).

        Args:
            accel: True to power the accelerometer.
            gyro: True to power the gyroscope.
        """
        val = (0 if accel else 0x38) | (0 if gyro else 0x07)
        self._connection.write_reg(self._REG_PWR_MGMT_2, val)

    def set_sleep(self, sleep=True):
        """Set or clear the SLEEP bit in PWR_MGMT_1.

        Args:
            sleep: True to enter sleep mode, False to wake.
        """
        val = self._connection.read_reg(self._REG_PWR_MGMT_1, 1)[0]
        val = (val | 0x40) if sleep else (val & ~0x40)
        self._connection.write_reg(self._REG_PWR_MGMT_1, val)

    def set_low_power(self, accel=False, gyro=False, accel_avg=0, gyro_avg=0):
        """Configure duty-cycled low-power operation.

        ``LP_EN`` is set last because it blocks most register writes.

        Args:
            accel: Duty-cycle the accelerometer.
            gyro: Duty-cycle the gyroscope.
            accel_avg: DEC3_CFG averaging code 0-3.
            gyro_avg: GYRO_AVGCFG averaging code 0-7.
        """
        self._lp_suspended = False
        val = self._connection.read_reg(self._REG_PWR_MGMT_1, 1)[0] & ~0x20
        self._connection.write_reg(self._REG_PWR_MGMT_1, val)
        self._wr(2, self._REG_ACCEL_CONFIG_2, accel_avg & 0x03)
        self._wr(2, self._REG_GYRO_CONFIG_2, gyro_avg & 0x07)
        self._connection.write_reg(self._REG_LP_CONFIG,
                                   0x40 | (0x20 if accel else 0) | (0x10 if gyro else 0))
        self._lp_en = bool(accel or gyro)
        if self._lp_en:
            self._connection.write_reg(self._REG_PWR_MGMT_1, val | 0x20)

    def set_gyro_offset(self, x, y, z):
        """Write the gyroscope offset-cancellation registers.

        Args:
            x: X offset in rad/s.
            y: Y offset in rad/s.
            z: Z offset in rad/s.
        """
        data = bytearray()
        for v in (x, y, z):
            code = int(round(v / _DEG_TO_RAD / 0.0305))
            code = max(-32768, min(32767, code))
            data += struct.pack('>h', code)
        self._lp_suspend()
        self._wr(2, self._REG_XG_OFFS_USRH, bytes(data))
        self._lp_resume()

    def get_accel_offset(self):
        """Read the 15-bit accelerometer offset codes (0.98 mg/LSB).

        The registers hold a per-part factory trim: read, adjust, write back.

        Returns:
            tuple: (x, y, z) signed 15-bit offset codes.
        """
        raw = self._rd(1, self._REG_XA_OFFS_H, 8)
        out = []
        for i in (0, 3, 6):
            code = (raw[i] << 7) | (raw[i + 1] >> 1)
            out.append(to_signed(code, 15))
        return tuple(out)

    def set_accel_offset(self, x, y, z):
        """Write the 15-bit accelerometer offset codes.

        Args:
            x: X offset code (signed 15-bit).
            y: Y offset code.
            z: Z offset code.
        """
        self._lp_suspend()
        for reg, v in ((0x14, x), (0x17, y), (0x1A, z)):
            code = v & 0x7FFF
            self._wr(1, reg, bytes([(code >> 7) & 0xFF, (code << 1) & 0xFE]))
        self._lp_resume()

    # -- auxiliary I²C master / magnetometer -----------------------------

    def _slv4_wait(self):
        for _ in range(100):
            status = self._connection.read_reg(self._REG_I2C_MST_STATUS, 1)[0]
            if status & 0x10:
                raise OSError(5, 'ICM20948 auxiliary I2C NACK')
            if status & 0x40:
                return
            time.sleep(0.001)
        raise OSError(110, 'ICM20948 auxiliary I2C transfer timed out')

    def aux_read_byte(self, address, register):
        """Read one byte from an auxiliary-bus device through slave 4.

        Args:
            address: 7-bit device address.
            register: Register inside the device.

        Returns:
            int: The byte read.
        """
        self._select_bank(3)
        self._connection.write_reg(self._REG_I2C_SLV4_ADDR, 0x80 | (address & 0x7F))
        self._connection.write_reg(self._REG_I2C_SLV4_REG, register & 0xFF)
        self._connection.write_reg(self._REG_I2C_SLV4_CTRL, 0x80)
        self._select_bank(0)
        self._slv4_wait()
        return self._rd(3, self._REG_I2C_SLV4_DI, 1)[0]

    def aux_write_byte(self, address, register, value):
        """Write one byte to an auxiliary-bus device through slave 4.

        Args:
            address: 7-bit device address.
            register: Register inside the device.
            value: Byte to write.
        """
        self._select_bank(3)
        self._connection.write_reg(self._REG_I2C_SLV4_ADDR, address & 0x7F)
        self._connection.write_reg(self._REG_I2C_SLV4_REG, register & 0xFF)
        self._connection.write_reg(self._REG_I2C_SLV4_DO, value & 0xFF)
        self._connection.write_reg(self._REG_I2C_SLV4_CTRL, 0x80)
        self._select_bank(0)
        self._slv4_wait()

    def enable_mag(self):
        """Enable the AK09916 magnetometer via the I²C master.

        Resets the magnetometer, verifies WIA2 == 0x09, selects continuous
        measurement 4 (100 Hz) and configures slave 0 to copy the 9-byte
        ``ST1..ST2`` block to ``EXT_SLV_SENS_DATA_00..08`` continuously.

        Raises:
            ValueError: If WIA2 does not read 0x09.
        """
        self._lp_suspend()
        ctrl = self._connection.read_reg(self._REG_USER_CTRL, 1)[0]
        self._connection.write_reg(self._REG_USER_CTRL, ctrl | 0x20)  # I2C_MST_EN
        self._wr(3, self._REG_I2C_MST_CTRL, 0x07)                     # 345.6 kHz
        self.aux_write_byte(self._AK_ADDR, self._AK_CNTL3, 0x01)      # soft reset
        time.sleep(0.002)
        wia = self.aux_read_byte(self._AK_ADDR, self._AK_WIA2)
        if wia != self._AK_WIA2_VALUE:
            raise ValueError('AK09916 WIA2: expected 0x{:02X}, got 0x{:02X}'.format(
                self._AK_WIA2_VALUE, wia))
        self.aux_write_byte(self._AK_ADDR, self._AK_CNTL2, 0x08)      # continuous 4
        self._select_bank(3)
        self._connection.write_reg(self._REG_I2C_SLV0_ADDR, 0x80 | self._AK_ADDR)
        self._connection.write_reg(self._REG_I2C_SLV0_REG, self._AK_ST1)
        self._connection.write_reg(self._REG_I2C_SLV0_CTRL, 0x89)
        self._select_bank(0)
        self._mag_enabled = True
        self._lp_resume()

    def mag_set_mode(self, mode):
        """Write the magnetometer CNTL2 mode through slave 4.

        Args:
            mode: 0 power-down, 1 single, 2/4/6 continuous 1-3, 8 continuous 4.
        """
        self.aux_write_byte(self._AK_ADDR, self._AK_CNTL2, mode & 0x1F)

    def _mag_block(self):
        if not self._mag_enabled:
            raise RuntimeError('Magnetometer not enabled. Call enable_mag() first.')
        return self._rd(0, self._REG_EXT_SLV_SENS_DATA_00, 9)

    def mag(self):
        """Read the magnetic field.

        Returns:
            tuple: (x, y, z) in µT, as the chip reports them.

        Raises:
            RuntimeError: If :meth:`enable_mag` has not been called, or the
                magnetic sensor overflowed (HOFL).
        """
        raw = self._mag_block()
        if raw[8] & 0x08:
            raise RuntimeError('Magnetometer overflow (HOFL)')
        mx, my, mz = struct.unpack('<hhh', raw[1:7])
        return (mx * self._MAG_SENSITIVITY, my * self._MAG_SENSITIVITY,
                mz * self._MAG_SENSITIVITY)

    def mag_raw(self):
        """Read the raw magnetometer values.

        Returns:
            tuple: (x, y, z) signed 16-bit counts.
        """
        return struct.unpack('<hhh', self._mag_block()[1:7])

    def mag_data_ready(self):
        """Check the magnetometer DRDY flag from the shadowed block.

        Returns:
            bool: True when ST1.DRDY is set.
        """
        return bool(self._mag_block()[0] & 0x01)

    # -- data ready / FIFO -----------------------------------------------

    def data_ready(self):
        """Check whether new raw data is available.

        Returns:
            bool: True when INT_STATUS_1.RAW_DATA_0_RDY_INT is set.
        """
        return bool(self._rd(0, self._REG_INT_STATUS_1, 1)[0] & 0x01)

    def enable_fifo(self, accel=True, gyro=True, temp=False):
        """Select FIFO sources, enable the FIFO and reset it.

        Args:
            accel: Write accelerometer data (6 bytes).
            gyro: Write gyroscope data (6 bytes).
            temp: Write temperature data (2 bytes).
        """
        self._lp_suspend()
        sources = (0x10 if accel else 0) | (0x0E if gyro else 0) | (0x01 if temp else 0)
        self._connection.write_reg(self._REG_FIFO_EN_2, sources)
        ctrl = self._connection.read_reg(self._REG_USER_CTRL, 1)[0]
        self._connection.write_reg(self._REG_USER_CTRL, ctrl | 0x40)
        self.reset_fifo()
        self._lp_resume()

    def set_fifo_mode(self, snapshot=False):
        """Select FIFO mode.

        Args:
            snapshot: True to stop when full, False to overwrite the oldest.
        """
        self._lp_suspend()
        self._connection.write_reg(self._REG_FIFO_MODE, 0x1F if snapshot else 0x00)
        self._lp_resume()

    def fifo_count(self):
        """Read the number of bytes in the FIFO.

        Returns:
            int: Byte count (0-4096).
        """
        raw = self._rd(0, self._REG_FIFO_COUNTH, 2)
        return ((raw[0] & 0x1F) << 8) | raw[1]

    def read_fifo(self, count=None):
        """Read bytes from the FIFO.

        Args:
            count: Maximum bytes to read (default: all available).

        Returns:
            bytes: FIFO data. See the spec: frame layout is not documented.
        """
        available = self.fifo_count()
        if count is None or count > available:
            count = available
        out = bytearray()
        while count > 0:
            n = min(count, 32)
            out += self._rd(0, self._REG_FIFO_R_W, n)
            count -= n
        return bytes(out)

    def reset_fifo(self):
        """Reset the FIFO by asserting and de-asserting FIFO_RST."""
        self._connection.write_reg(self._REG_FIFO_RST, 0x1F)
        self._connection.write_reg(self._REG_FIFO_RST, 0x00)

    # -- wake-on-motion / interrupts -------------------------------------

    def set_wake_on_motion(self, threshold_mg, enabled=True):
        """Configure wake-on-motion.

        Needs the accelerometer on with its filter enabled.

        Args:
            threshold_mg: Threshold in mg (4 mg/LSB, 0-1020).
            enabled: True to arm, False to disarm.
        """
        self._lp_suspend()
        thr = max(0, min(255, int(round(threshold_mg / 4.0))))
        self._wr(2, self._REG_ACCEL_WOM_THR, thr)
        self._wr(2, self._REG_ACCEL_INTEL_CTRL, 0x03 if enabled else 0x00)
        if enabled:
            self.enable_interrupt(self.SOURCE_WAKE_ON_MOTION)
        else:
            self.disable_interrupt(self.SOURCE_WAKE_ON_MOTION)
        self._lp_resume()

    def set_interrupt_pin(self, active_low=False, open_drain=False, latch=False,
                          any_read_clears=False):
        """Configure the INT1 pin (INT_PIN_CFG).

        Args:
            active_low: INT1 active low.
            open_drain: Open-drain output instead of push-pull.
            latch: Hold the level until the status is cleared.
            any_read_clears: Any read clears INT_STATUS.
        """
        val = ((0x80 if active_low else 0) | (0x40 if open_drain else 0) |
               (0x20 if latch else 0) | (0x10 if any_read_clears else 0))
        self._connection.write_reg(self._REG_INT_PIN_CFG, val)

    def enable_interrupt(self, source):
        """Enable one interrupt source (``SOURCE_*`` constant).

        Args:
            source: One of the ``SOURCE_*`` constants.
        """
        reg, mask = self._INT_ENABLE_MAP[source]
        val = self._connection.read_reg(reg, 1)[0]
        self._connection.write_reg(reg, val | mask)

    def disable_interrupt(self, source):
        """Disable one interrupt source (``SOURCE_*`` constant).

        Args:
            source: One of the ``SOURCE_*`` constants.
        """
        reg, mask = self._INT_ENABLE_MAP[source]
        val = self._connection.read_reg(reg, 1)[0]
        self._connection.write_reg(reg, val & ~mask)

    def poll_interrupt(self):
        """Read (and clear) INT_STATUS, INT_STATUS_1 and INT_STATUS_2.

        Returns:
            int: Combined status using the ``SOURCE_*`` bit layout.
        """
        s0, s1, s2 = self._rd(0, self._REG_INT_STATUS, 3)
        status = 0
        if s1 & 0x01:
            status |= self.SOURCE_DATA_READY
        if s0 & 0x08:
            status |= self.SOURCE_WAKE_ON_MOTION
        if s0 & 0x01:
            status |= self.SOURCE_I2C_MASTER
        if s0 & 0x04:
            status |= self.SOURCE_PLL_READY
        if s2 & 0x1F:
            status |= self.SOURCE_FIFO_OVERFLOW
        return status

    def on_interrupt(self, callback):
        """Subscribe to INT1 assertions.

        Uses the connection's ``int_pin`` when present, otherwise falls back
        to a 5 ms polling thread.

        Args:
            callback: Function called with the status int from
                :meth:`poll_interrupt`.
        """
        self._int_callback = callback
        int_pin = getattr(self._connection, 'int_pin', None)
        if int_pin is not None and hasattr(int_pin, 'on_edge'):
            self._int_handler = lambda pin: self._dispatch_int()
            int_pin.on_edge(self._int_handler, getattr(int_pin, 'RISING', 1))
        else:
            self._start_polling_fallback()

    def off_interrupt(self):
        """Unsubscribe from INT1 assertions."""
        self._int_callback = None
        int_pin = getattr(self._connection, 'int_pin', None)
        handler = getattr(self, '_int_handler', None)
        if int_pin is not None and handler is not None and hasattr(int_pin, 'off_edge'):
            int_pin.off_edge(handler)
        elif getattr(self, '_poll_timer', None) is not None:
            try:
                self._poll_timer.deinit()
            except Exception:
                pass
            self._poll_timer = None

    def _dispatch_int(self):
        cb = self._int_callback
        if cb is None:
            return
        try:
            cb(self.poll_interrupt())
        except Exception:
            pass

    def _start_polling_fallback(self):
        try:
            import _thread
        except ImportError:
            return
        stop = [False]

        def _loop():
            while not stop[0]:
                self._dispatch_int()
                time.sleep(0.005)
        _thread.start_new_thread(_loop, ())
        self._poll_timer = type('T', (), {'deinit': lambda s: stop.__setitem__(0, True)})()

    # -- self-test / calibration / reset ---------------------------------

    def _average_raw(self, samples=5):
        sa = [0, 0, 0]
        sg = [0, 0, 0]
        for _ in range(samples):
            a = self.accel_raw()
            g = self.gyro_raw()
            for i in range(3):
                sa[i] += a[i]
                sg[i] += g[i]
            time.sleep(0.011)
        return [v / samples for v in sa], [v / samples for v in sg]

    def self_test(self):
        """Run the self-test enable procedure and return the raw response.

        Configures ±2 g / ±250 dps, averages samples with and without the
        self-test actuation and restores the configuration. The datasheet
        gives no pass/fail limits, so none is evaluated.

        Returns:
            tuple: ``((ax, ay, az), (gx, gy, gz))`` — raw response (enabled
            minus disabled) per axis.
        """
        saved = (self._accel_fs, self._gyro_fs)
        self.configure_accel(0)
        self.configure_gyro(0)
        time.sleep(0.05)
        a0, g0 = self._average_raw()
        self._lp_suspend()
        self._wr(2, self._REG_GYRO_CONFIG_2, 0x38)
        self._wr(2, self._REG_ACCEL_CONFIG_2, 0x1C)
        time.sleep(0.05)
        a1, g1 = self._average_raw()
        self._wr(2, self._REG_GYRO_CONFIG_2, 0x00)
        self._wr(2, self._REG_ACCEL_CONFIG_2, 0x00)
        self._lp_resume()
        self.configure_accel(saved[0])
        self.configure_gyro(saved[1])
        return (tuple(a1[i] - a0[i] for i in range(3)),
                tuple(g1[i] - g0[i] for i in range(3)))

    def read_timebase_correction(self):
        """Read the per-part PLL time-base correction.

        Returns:
            float: Deviation in percent (0.079 %/LSB, signed).
        """
        raw = self._rd(1, self._REG_TIMEBASE_CORRECTION_PLL, 1)[0]
        return to_signed(raw, 8) * 0.079

    def reset(self):
        """Issue DEVICE_RESET and wait.

        The chip is left in sleep with default registers; create a new
        driver instance afterwards to run the initialization again.
        """
        self._connection.write_reg(self._REG_PWR_MGMT_1, 0x80)
        time.sleep(0.1)
        self._bank = 0
        self._lp_en = False
        self._lp_suspended = False
        self._mag_enabled = False
