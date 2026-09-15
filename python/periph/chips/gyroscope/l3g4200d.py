import math
import time


_REG_WHO_AM_I     = 0x0F
_REG_CTRL_REG1    = 0x20
_REG_CTRL_REG2    = 0x21
_REG_CTRL_REG3    = 0x22
_REG_CTRL_REG4    = 0x23
_REG_CTRL_REG5    = 0x24
_REG_REFERENCE    = 0x25
_REG_OUT_TEMP     = 0x26
_REG_STATUS       = 0x27
_REG_OUT_X_L      = 0x28
_REG_OUT_X_H      = 0x29
_REG_OUT_Y_L      = 0x2A
_REG_OUT_Y_H      = 0x2B
_REG_OUT_Z_L      = 0x2C
_REG_OUT_Z_H      = 0x2D
_REG_FIFO_CTRL    = 0x2E
_REG_FIFO_SRC     = 0x2F
_REG_INT1_CFG     = 0x30
_REG_INT1_SRC     = 0x31
_REG_INT1_THS_XH  = 0x32
_REG_INT1_THS_XL  = 0x33
_REG_INT1_THS_YH  = 0x34
_REG_INT1_THS_YL  = 0x35
_REG_INT1_THS_ZH  = 0x36
_REG_INT1_THS_ZL  = 0x37
_REG_INT1_DURATION = 0x38

_WHO_AM_I_EXPECTED = 0xD3

# Sensitivity per full-scale range, dps/digit.
_SENSITIVITY = {
    250:  8.75e-3,
    500:  17.5e-3,
    2000: 70.0e-3,
}

# CTRL_REG1 default: DR=00 (100 Hz), BW=00 (12.5 Hz), PD=1, Zen=Yen=Xen=1
_CTRL_REG1_DEFAULT = 0x0F
# CTRL_REG4 default: BDU=1, FS=00 (±250 dps), 4-wire SPI, LSB at lower address.
_CTRL_REG4_DEFAULT = 0x80

_DPS_TO_RAD = math.pi / 180.0


def _int16(data):
    value = data[0] | (data[1] << 8)
    if value & 0x8000:
        value -= 0x10000
    return value


class L3G4200DMinimal:
    """L3G4200D three-axis MEMS gyroscope — minimal interface.

    Provides angular rate readings on the X, Y, and Z axes with no
    configuration beyond the connection. I²C address is 0x68 (SA0=GND) or
    0x69 (SA0=VDD). SPI uses Mode 3 (CPOL=CPHA=1) by default.

    Default configuration (baked in at construction):
        - 100 Hz ODR, 12.5 Hz LPF2 cutoff (DR=00, BW=00)
        - ±250 dps full scale
        - BDU=1 (block data update — hold registers until MSB+LSB read)
        - All axes enabled, normal power mode
        - FIFO disabled (bypass)
        - HPF disabled

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
        bus_type: ``'i2c'`` (default) or ``'spi'``. SPI writes mask bit 7
            of the register address and reads set bit 7 + bit 6 for
            auto-increment on multi-byte reads.
    """

    def __init__(self, connection, bus_type='i2c'):
        self._connection = connection
        self._bus_type = bus_type
        if self._read_reg(_REG_WHO_AM_I, 1)[0] != _WHO_AM_I_EXPECTED:
            raise ValueError(
                'L3G4200D not found: WHO_AM_I expected 0x{:02X}'.format(_WHO_AM_I_EXPECTED))
        self._full_scale = 250
        self._write_reg(_REG_CTRL_REG4, _CTRL_REG4_DEFAULT)
        self._write_reg(_REG_CTRL_REG1, _CTRL_REG1_DEFAULT)

    def _write_reg(self, reg, value):
        if self._bus_type == 'spi':
            reg = reg & 0x3F
        self._connection.write(bytes([reg, value & 0xFF]))

    def _read_reg(self, reg, n):
        if self._bus_type == 'spi':
            sub_addr = reg | 0xC0  # READ=1, MS=1 (auto-increment)
        else:
            sub_addr = reg | 0x80  # MSB set = auto-increment on I²C
        return self._connection.write_read(bytes([sub_addr & 0xFF]), n)

    def _sensitivity(self):
        return _SENSITIVITY[self._full_scale]

    def angular_rate(self):
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


class L3G4200DFull(L3G4200DMinimal):
    """L3G4200D full interface — extends L3G4200DMinimal with full configuration,
    FIFO, high-pass filter, interrupts, axis-enable, and power-mode control.

    Adds ODR/bandwidth/full-scale configuration, FIFO with all five modes
    and watermark, high-pass filter with selectable cutoff, per-axis
    interrupt generation with threshold and duration, INT1/DRDY pin routing,
    and access to temperature and status registers.

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
        bus_type: ``'i2c'`` (default) or ``'spi'``.
    """

    ODR_100_HZ = 0
    ODR_200_HZ = 1
    ODR_400_HZ = 2
    ODR_800_HZ = 3

    BW_12_5_HZ = 0
    BW_25_HZ   = 1
    BW_25_HZ_2 = 2
    BW_25_HZ_3 = 3
    BW_50_HZ   = 2
    BW_70_HZ   = 3

    FS_250_DPS  = 250
    FS_500_DPS  = 500
    FS_2000_DPS = 2000

    FIFO_BYPASS            = 0
    FIFO_FIFO              = 1
    FIFO_STREAM            = 2
    FIFO_STREAM_TO_FIFO    = 3
    FIFO_BYPASS_TO_STREAM  = 4

    HPM_NORMAL        = 0
    HPM_REFERENCE     = 1
    HPM_NORMAL_ALT    = 2
    HPM_AUTORESET     = 3

    INT1_SEL_DATA       = 0
    INT1_SEL_HPF        = 1
    INT1_SEL_LPF2       = 2
    INT1_SEL_HPF_LPF2   = 3

    OUT_SEL_DATA        = 0
    OUT_SEL_HPF         = 1
    OUT_SEL_LPF2        = 2
    OUT_SEL_HPF_LPF2    = 3

    def __init__(self, connection, bus_type='i2c'):
        super().__init__(connection, bus_type)
        self._odr = 0
        self._bw = 0
        self._threshold_raw = 0

    def configure(self, odr=100, bandwidth=0, full_scale=250):
        """Configure ODR, LPF2 bandwidth, and full scale in one call.

        Args:
            odr: Output data rate in Hz (100, 200, 400, or 800). Default 100.
            bandwidth: LPF2 bandwidth code 0-3 (depends on ODR). Default 0.
            full_scale: Full-scale range in dps (250, 500, or 2000).
                Default 250.
        """
        odr_map = {100: 0, 200: 1, 400: 2, 800: 3}
        if odr not in odr_map:
            raise ValueError('odr must be 100, 200, 400 or 800 Hz')
        if full_scale not in (250, 500, 2000):
            raise ValueError('full_scale must be 250, 500 or 2000 dps')
        self._odr = odr_map[odr]
        self._bw = bandwidth & 0x3
        self._full_scale = full_scale
        ctrl1 = _CTRL_REG1_DEFAULT | ((self._odr & 0x3) << 6) | ((self._bw & 0x3) << 4)
        self._write_reg(_REG_CTRL_REG1, ctrl1)
        fs_bits = 0 if full_scale == 250 else (1 if full_scale == 500 else 2)
        ctrl4 = _CTRL_REG4_DEFAULT | ((fs_bits & 0x3) << 4)
        self._write_reg(_REG_CTRL_REG4, ctrl4)

    def set_full_scale(self, full_scale):
        """Update the full-scale range.

        Args:
            full_scale: 250, 500, or 2000 dps.
        """
        if full_scale not in (250, 500, 2000):
            raise ValueError('full_scale must be 250, 500 or 2000 dps')
        self._full_scale = full_scale
        fs_bits = 0 if full_scale == 250 else (1 if full_scale == 500 else 2)
        ctrl4 = self._read_reg(_REG_CTRL_REG4, 1)[0]
        ctrl4 = (ctrl4 & 0xCF) | ((fs_bits & 0x3) << 4)
        self._write_reg(_REG_CTRL_REG4, ctrl4)

    def who_am_i(self):
        """Read WHO_AM_I (0x0F).

        Returns:
            int: ``0xD3`` for a genuine L3G4200D.
        """
        return self._read_reg(_REG_WHO_AM_I, 1)[0]

    def status(self):
        """Read STATUS_REG (0x27) raw byte.

        Bit fields: ZYXOR[7], ZOR[6], YOR[5], XOR[4], ZYXDA[3], ZDA[2],
        YDA[1], XDA[0].

        Returns:
            int: Raw status byte.
        """
        return self._read_reg(_REG_STATUS, 1)[0]

    def data_ready(self):
        """Check whether a new X/Y/Z sample is ready.

        Returns:
            bool: True if STATUS_REG.ZYXDA (bit 3) is set.
        """
        return bool(self.status() & 0x08)

    def temperature(self):
        """Read the relative temperature count.

        OUT_TEMP is an 8-bit signed value with a -1 °C/digit scale; there is
        no absolute calibration — useful only for tracking drift.

        Returns:
            int: Signed 8-bit temperature count.
        """
        return self._read_reg(_REG_OUT_TEMP, 1)[0]

    def power_down(self):
        """Enter power-down mode (PD=0 in CTRL_REG1)."""
        ctrl1 = self._read_reg(_REG_CTRL_REG1, 1)[0] & 0xF7
        self._write_reg(_REG_CTRL_REG1, ctrl1)

    def wake_up(self):
        """Wake from power-down (PD=1); previously enabled axes restored."""
        ctrl1 = self._read_reg(_REG_CTRL_REG1, 1)[0] | 0x08
        self._write_reg(_REG_CTRL_REG1, ctrl1)

    def sleep(self):
        """Enter sleep mode (PD=1, all axes disabled)."""
        self._write_reg(_REG_CTRL_REG1, 0x08)

    def enable_axes(self, x=True, y=True, z=True):
        """Enable or disable individual axes (Xen/Yen/Zen in CTRL_REG1).

        Args:
            x: Enable X axis.
            y: Enable Y axis.
            z: Enable Z axis.
        """
        ctrl1 = self._read_reg(_REG_CTRL_REG1, 1)[0]
        ctrl1 = (ctrl1 & 0xF8) | (0x04 if z else 0) | (0x02 if y else 0) | (0x01 if x else 0)
        self._write_reg(_REG_CTRL_REG1, ctrl1)

    def enable_fifo(self, mode=0, watermark=0):
        """Configure and enable the FIFO.

        Args:
            mode: FIFO mode 0-4 (0=bypass/disable, 1=FIFO, 2=stream,
                3=stream-to-FIFO, 4=bypass-to-stream). Default 0 disables.
            watermark: Watermark threshold 0-31 (number of stored samples).
        """
        if mode < 0 or mode > 4:
            raise ValueError('mode must be 0..4')
        if watermark < 0 or watermark > 31:
            raise ValueError('watermark must be 0..31')
        ctrl5 = self._read_reg(_REG_CTRL_REG5, 1)[0] | 0x40  # FIFO_EN
        self._write_reg(_REG_CTRL_REG5, ctrl5)
        fifo_ctrl = ((mode & 0x7) << 5) | (watermark & 0x1F)
        self._write_reg(_REG_FIFO_CTRL, fifo_ctrl)

    def disable_fifo(self):
        """Disable the FIFO (clear FIFO_EN and put it in bypass mode)."""
        ctrl5 = self._read_reg(_REG_CTRL_REG5, 1)[0] & ~0x40
        self._write_reg(_REG_CTRL_REG5, ctrl5)
        self._write_reg(_REG_FIFO_CTRL, 0x00)

    def fifo_samples(self):
        """Read FSS[4:0] from FIFO_SRC_REG.

        Returns:
            int: Number of stored samples in the FIFO (0-31).
        """
        return self._read_reg(_REG_FIFO_SRC, 1)[0] & 0x1F

    def read_fifo(self):
        """Drain all stored FIFO samples and return them as rad/s tuples.

        Returns:
            list: Each entry is ``(x_rad_s, y_rad_s, z_rad_s)``.
        """
        n = self.fifo_samples()
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

    def enable_highpass(self, mode=0, cutoff=0):
        """Enable the high-pass filter on the output path.

        Args:
            mode: HPF mode 0-3 (HPM field in CTRL_REG2).
            cutoff: HPF cutoff code 0-9 (HPCF field in CTRL_REG2; actual
                cutoff depends on ODR — see datasheet Table 27).
        """
        if mode < 0 or mode > 3:
            raise ValueError('mode must be 0..3')
        if cutoff < 0 or cutoff > 9:
            raise ValueError('cutoff must be 0..9')
        ctrl2 = ((mode & 0x3) << 4) | (cutoff & 0x0F)
        self._write_reg(_REG_CTRL_REG2, ctrl2)
        ctrl5 = self._read_reg(_REG_CTRL_REG5, 1)[0] | 0x10  # HPen
        self._write_reg(_REG_CTRL_REG5, ctrl5)

    def disable_highpass(self):
        """Clear HPen in CTRL_REG5."""
        ctrl5 = self._read_reg(_REG_CTRL_REG5, 1)[0] & ~0x10
        self._write_reg(_REG_CTRL_REG5, ctrl5)

    def set_interrupt(self, x_high=False, x_low=False, y_high=False, y_low=False,
                      z_high=False, z_low=False, and_mode=False, latch=False):
        """Configure INT1_CFG — which axis/direction events fire INT1.

        Args:
            x_high: Enable X high-event interrupt.
            x_low: Enable X low-event interrupt.
            y_high: Enable Y high-event interrupt.
            y_low: Enable Y low-event interrupt.
            z_high: Enable Z high-event interrupt.
            z_low: Enable Z low-event interrupt.
            and_mode: AND combination of events (False=OR).
            latch: Latch interrupt request until INT1_SRC is read.
        """
        cfg = 0
        if and_mode:
            cfg |= 0x80
        if latch:
            cfg |= 0x40
        if z_high:
            cfg |= 0x20
        if z_low:
            cfg |= 0x10
        if y_high:
            cfg |= 0x08
        if y_low:
            cfg |= 0x04
        if x_high:
            cfg |= 0x02
        if x_low:
            cfg |= 0x01
        self._write_reg(_REG_INT1_CFG, cfg)
        if cfg & 0x3F:
            ctrl3 = self._read_reg(_REG_CTRL_REG3, 1)[0] | 0x80
            self._write_reg(_REG_CTRL_REG3, ctrl3)

    def set_threshold(self, axis, threshold_dps):
        """Set the interrupt threshold for one axis.

        Args:
            axis: ``'x'``, ``'y'``, or ``'z'``.
            threshold_dps: Threshold in dps. The 15-bit raw value is
                ``int(threshold_dps / sensitivity)``.
        """
        raw = int(threshold_dps / self._sensitivity()) & 0x7FFF
        self._threshold_raw = raw
        if axis == 'x':
            hi, lo = _REG_INT1_THS_XH, _REG_INT1_THS_XL
        elif axis == 'y':
            hi, lo = _REG_INT1_THS_YH, _REG_INT1_THS_YL
        elif axis == 'z':
            hi, lo = _REG_INT1_THS_ZH, _REG_INT1_THS_ZL
        else:
            raise ValueError("axis must be 'x', 'y' or 'z'")
        self._write_reg(hi, (raw >> 8) & 0x7F)
        self._write_reg(lo, raw & 0xFF)

    def set_duration(self, samples, wait=False):
        """Set INT1_DURATION.

        Args:
            samples: Duration 0-127 (number of ODR cycles the condition must
                be true before INT1 fires).
            wait: WAIT bit — if True, INT1 stays asserted until INT1_SRC is
                read; if False, INT1 deasserts as soon as the condition
                stops being true.
        """
        if samples < 0 or samples > 127:
            raise ValueError('samples must be 0..127')
        val = ((1 if wait else 0) << 7) | (samples & 0x7F)
        self._write_reg(_REG_INT1_DURATION, val)

    def read_int_source(self):
        """Read INT1_SRC; reading clears the interrupt-active bit.

        Returns:
            int: Raw INT1_SRC byte.
        """
        return self._read_reg(_REG_INT1_SRC, 1)[0]

    def set_data_ready_pin(self, enable=True):
        """Route the data-ready signal to the DRDY/INT2 pin.

        Args:
            enable: True to enable, False to disable.
        """
        ctrl3 = self._read_reg(_REG_CTRL_REG3, 1)[0]
        if enable:
            ctrl3 |= 0x08
        else:
            ctrl3 &= ~0x08
        self._write_reg(_REG_CTRL_REG3, ctrl3)
