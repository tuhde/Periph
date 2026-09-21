import math
import time


_REG_WHO_AM_I      = 0x0F
_REG_CTRL_REG1     = 0x20
_REG_CTRL_REG2     = 0x21
_REG_CTRL_REG3     = 0x22
_REG_CTRL_REG4     = 0x23
_REG_CTRL_REG5     = 0x24
_REG_REFERENCE     = 0x25
_REG_OUT_TEMP      = 0x26
_REG_STATUS        = 0x27
_REG_OUT_X_L       = 0x28
_REG_OUT_X_H       = 0x29
_REG_OUT_Y_L       = 0x2A
_REG_OUT_Y_H       = 0x2B
_REG_OUT_Z_L       = 0x2C
_REG_OUT_Z_H       = 0x2D
_REG_FIFO_CTRL     = 0x2E
_REG_FIFO_SRC      = 0x2F
_REG_INT1_CFG      = 0x30
_REG_INT1_SRC      = 0x31
_REG_INT1_TSH_XH   = 0x32
_REG_INT1_TSH_XL   = 0x33
_REG_INT1_TSH_YH   = 0x34
_REG_INT1_TSH_YL   = 0x35
_REG_INT1_TSH_ZH   = 0x36
_REG_INT1_TSH_ZL   = 0x37
_REG_INT1_DURATION = 0x38

_WHO_AM_I_L3GD20  = 0xD4
_WHO_AM_I_L3GD20H = 0xD7

_SENSITIVITY = {
    250:  8.75e-3,
    500:  17.5e-3,
    2000: 70.0e-3,
}

_CTRL_REG1_DEFAULT = 0x0F
_CTRL_REG4_DEFAULT = 0x80

_DPS_TO_RAD = math.pi / 180.0


def _int16(data):
    value = data[0] | (data[1] << 8)
    if value & 0x8000:
        value -= 0x10000
    return value


class L3GD20HMinimal:
    """L3GD20H (and L3GD20) three-axis MEMS gyroscope — minimal interface.

    Provides angular rate readings on the X, Y, and Z axes with no
    configuration beyond the connection. I²C address is 0x6A (SA0/SDO=GND) or
    0x6B (SA0/SDO=VCC). SPI uses Mode 3 (CPOL=CPHA=1) by default.

    Default configuration (baked in at construction):
        - 95 Hz ODR, default bandwidth (DR=00, BW=00)
        - ±250 dps full scale (sensitivity 8.75 mdps/digit)
        - BDU=1 (block data update — hold registers until MSB+LSB read)
        - All axes enabled, normal power mode
        - 250 ms startup delay for gyroscope stabilization

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
        bus_type: ``'i2c'`` (default) or ``'spi'``. SPI writes mask bit 7
            of the register address and reads set bit 7 + bit 6 for
            auto-increment on multi-byte reads.
    """

    def __init__(self, connection, bus_type='i2c'):
        self._connection = connection
        self._bus_type = bus_type
        who = self._read_reg(_REG_WHO_AM_I, 1)[0]
        if who not in (_WHO_AM_I_L3GD20, _WHO_AM_I_L3GD20H):
            raise ValueError(
                'L3GD20H not found: WHO_AM_I expected 0xD4 (L3GD20) or 0xD7 (L3GD20H), got 0x{:02X}'.format(who))
        self._full_scale = 250
        self._write_reg(_REG_CTRL_REG4, _CTRL_REG4_DEFAULT)
        self._write_reg(_REG_CTRL_REG1, _CTRL_REG1_DEFAULT)
        time.sleep(0.250)

    def _write_reg(self, reg, value):
        if self._bus_type == 'spi':
            reg = reg & 0x3F
        self._connection.write(bytes([reg, value & 0xFF]))

    def _read_reg(self, reg, n):
        if self._bus_type == 'spi':
            sub_addr = reg | 0xC0
        elif n > 1:
            sub_addr = reg | 0x80
        else:
            sub_addr = reg
        return self._connection.write_read(bytes([sub_addr & 0xFF]), n)

    def _sensitivity(self):
        return _SENSITIVITY[self._full_scale]

    def gyro(self):
        """Read angular rate on all three axes as a single burst transaction.

        Burst-reads OUT_X_L through OUT_Z_H (registers 0x28–0x2D, 6 bytes,
        little-endian), unpacks the three signed 16-bit values, and converts
        them to rad/s using the current full-scale sensitivity.

        Returns:
            tuple: ``(x_rad_s, y_rad_s, z_rad_s)`` angular rates.
        """
        data = self._read_reg(_REG_OUT_X_L, 6)
        sens = self._sensitivity()
        x_dps = _int16(data[0:2]) * sens
        y_dps = _int16(data[2:4]) * sens
        z_dps = _int16(data[4:6]) * sens
        return (x_dps * _DPS_TO_RAD, y_dps * _DPS_TO_RAD, z_dps * _DPS_TO_RAD)


class L3GD20HFull(L3GD20HMinimal):
    """L3GD20H full interface — extends L3GD20HMinimal with full configuration,
    FIFO, high-pass filter, interrupts, axis-enable, and power-mode control.

    Adds ODR/bandwidth/full-scale configuration, FIFO with all five modes
    and watermark, high-pass filter with selectable cutoff, per-axis
    interrupt generation with threshold and duration, INT1 pin routing,
    and access to temperature and status registers.

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
        bus_type: ``'i2c'`` (default) or ``'spi'``.
    """

    ODR_95_HZ   = 0
    ODR_190_HZ  = 1
    ODR_380_HZ  = 2
    ODR_760_HZ  = 3

    BW_DEFAULT = 0

    FS_250_DPS  = 250
    FS_500_DPS  = 500
    FS_2000_DPS = 2000

    FIFO_BYPASS            = 0
    FIFO_FIFO              = 1
    FIFO_STREAM            = 2
    FIFO_BYPASS_TO_STREAM  = 3
    FIFO_STREAM_TO_FIFO    = 7

    HPM_NORMAL       = 0
    HPM_REFERENCE    = 1
    HPM_NORMAL_ALT   = 2
    HPM_AUTORESET    = 3

    POWER_NORMAL    = 'normal'
    POWER_SLEEP     = 'sleep'
    POWER_POWERDOWN = 'power_down'

    def __init__(self, connection, bus_type='i2c'):
        super().__init__(connection, bus_type)
        self._odr = 0
        self._bw = 0
        self._threshold_raw = 0

    def configure(self, odr=0, bw=0, full_scale=0):
        """Configure ODR, bandwidth, and full scale in one call.

        Args:
            odr: Output data rate code 0-3 (95/190/380/760 Hz). Default 0.
            bw: Bandwidth selection code 0-3 (ODR-dependent; see datasheet Table 21).
                Default 0.
            full_scale: Full-scale code 0=±250, 1=±500, 2=±2000 dps. Default 0.
        """
        if odr < 0 or odr > 3:
            raise ValueError('odr must be 0..3')
        if bw < 0 or bw > 3:
            raise ValueError('bw must be 0..3')
        if full_scale < 0 or full_scale > 2:
            raise ValueError('full_scale must be 0..2')
        self._odr = odr
        self._bw = bw
        fs_map = {0: 250, 1: 500, 2: 2000}
        self._full_scale = fs_map[full_scale]
        ctrl1 = _CTRL_REG1_DEFAULT | ((self._odr & 0x3) << 6) | ((self._bw & 0x3) << 4)
        self._write_reg(_REG_CTRL_REG1, ctrl1)
        ctrl4 = _CTRL_REG4_DEFAULT | ((full_scale & 0x3) << 4)
        self._write_reg(_REG_CTRL_REG4, ctrl4)

    def gyro_raw(self):
        """Read raw 16-bit signed angular rate values.

        Returns:
            tuple: ``(x_raw, y_raw, z_raw)`` signed 16-bit integers.
        """
        data = self._read_reg(_REG_OUT_X_L, 6)
        x = _int16(data[0:2])
        y = _int16(data[2:4])
        z = _int16(data[4:6])
        return (x, y, z)

    def temperature(self):
        """Read the relative temperature count.

        OUT_TEMP is an 8-bit signed value with 1 LSB/°C sensitivity. There is
        no absolute calibration — it represents change from the device's
        power-on temperature baseline. Do not convert to absolute Celsius.

        Returns:
            int: Signed 8-bit temperature count.
        """
        raw = self._read_reg(_REG_OUT_TEMP, 1)[0]
        if raw & 0x80:
            raw -= 0x100
        return raw

    def data_ready(self):
        """Check whether a new X/Y/Z sample is ready.

        Returns:
            bool: True if STATUS_REG.ZYXDA (bit 3) is set.
        """
        return bool(self._read_reg(_REG_STATUS, 1)[0] & 0x08)

    def configure_hp_filter(self, mode=0, cutoff=0):
        """Configure the high-pass filter (CTRL_REG2).

        Args:
            mode: HPF mode 0-3 (HPM[1:0] in CTRL_REG2). Default 0.
            cutoff: HPF cutoff code 0-15 (HPCF[3:0] in CTRL_REG2; actual
                cutoff depends on ODR — see datasheet Table 21). Default 0.
        """
        if mode < 0 or mode > 3:
            raise ValueError('mode must be 0..3')
        if cutoff < 0 or cutoff > 15:
            raise ValueError('cutoff must be 0..15')
        ctrl2 = ((mode & 0x3) << 4) | (cutoff & 0x0F)
        self._write_reg(_REG_CTRL_REG2, ctrl2)

    def enable_hp_filter(self, enable=True):
        """Enable or disable the high-pass filter on the output path.

        Args:
            enable: True to enable (sets HPen in CTRL_REG5), False to disable.
        """
        ctrl5 = self._read_reg(_REG_CTRL_REG5, 1)[0]
        if enable:
            ctrl5 |= 0x10
        else:
            ctrl5 &= ~0x10
        self._write_reg(_REG_CTRL_REG5, ctrl5)

    def configure_fifo(self, mode=0, watermark=0):
        """Configure the FIFO (FIFO_CTRL_REG).

        Args:
            mode: FIFO mode 0=Bypass, 1=FIFO, 2=Stream, 3=Bypass-to-Stream,
                7=Stream-to-FIFO. Default 0.
            watermark: Watermark threshold 0-31 (WTM[4:0]). Default 0.
        """
        valid_modes = (0, 1, 2, 3, 7)
        if mode not in valid_modes:
            raise ValueError('mode must be 0, 1, 2, 3, or 7')
        if watermark < 0 or watermark > 31:
            raise ValueError('watermark must be 0..31')
        ctrl5 = self._read_reg(_REG_CTRL_REG5, 1)[0] | 0x40
        self._write_reg(_REG_CTRL_REG5, ctrl5)
        fifo_ctrl = ((mode & 0x7) << 5) | (watermark & 0x1F)
        self._write_reg(_REG_FIFO_CTRL, fifo_ctrl)

    def enable_fifo(self, enable=True):
        """Enable or disable the FIFO (FIFO_EN bit in CTRL_REG5).

        Args:
            enable: True to enable FIFO, False to disable and clear to bypass.
        """
        ctrl5 = self._read_reg(_REG_CTRL_REG5, 1)[0]
        if enable:
            ctrl5 |= 0x40
        else:
            ctrl5 &= ~0x40
            self._write_reg(_REG_FIFO_CTRL, 0x00)
        self._write_reg(_REG_CTRL_REG5, ctrl5)

    def fifo_level(self):
        """Read number of unread samples in FIFO (FIFO_SRC_REG FSS[4:0]).

        Returns:
            int: Number of stored samples (0-31).
        """
        return self._read_reg(_REG_FIFO_SRC, 1)[0] & 0x1F

    def read_fifo(self):
        """Read all available FIFO samples and return as rad/s tuples.

        Each burst read of OUT_X_L through OUT_Z_H pops the oldest entry.
        Reads FIFO_SRC_REG to determine sample count, then burst-reads all.

        Returns:
            list: Each entry is ``(x_rad_s, y_rad_s, z_rad_s)``.
        """
        n = self.fifo_level()
        if n == 0:
            return []
        sens = self._sensitivity()
        data = self._read_reg(_REG_OUT_X_L, n * 6)
        result = []
        for i in range(n):
            offset = i * 6
            x_dps = _int16(data[offset:offset + 2]) * sens
            y_dps = _int16(data[offset + 2:offset + 4]) * sens
            z_dps = _int16(data[offset + 4:offset + 6]) * sens
            result.append((x_dps * _DPS_TO_RAD, y_dps * _DPS_TO_RAD, z_dps * _DPS_TO_RAD))
        return result

    def set_power_mode(self, mode):
        """Set the power mode (CTRL_REG1 PD and axis enable bits).

        Args:
            mode: ``'normal'`` (PD=1, all axes on), ``'sleep'`` (PD=1, all axes off),
                or ``'power_down'`` (PD=0).
        """
        if mode == self.POWER_NORMAL:
            ctrl1 = self._read_reg(_REG_CTRL_REG1, 1)[0]
            ctrl1 = (ctrl1 & 0xF0) | 0x0F
            self._write_reg(_REG_CTRL_REG1, ctrl1)
        elif mode == self.POWER_SLEEP:
            ctrl1 = self._read_reg(_REG_CTRL_REG1, 1)[0]
            ctrl1 = (ctrl1 & 0xF8) | 0x08
            self._write_reg(_REG_CTRL_REG1, ctrl1)
        elif mode == self.POWER_POWERDOWN:
            ctrl1 = self._read_reg(_REG_CTRL_REG1, 1)[0] & 0xF7
            self._write_reg(_REG_CTRL_REG1, ctrl1)
        else:
            raise ValueError("mode must be 'normal', 'sleep', or 'power_down'")