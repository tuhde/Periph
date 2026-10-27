"""LSM303DLHC — 3-axis accelerometer + 3-axis magnetometer e-compass (STMicroelectronics).

System-in-package combining a three-axis MEMS accelerometer (I²C address 0x19)
and a three-axis magnetometer (I²C address 0x1E) behind two independent I²C
slave addresses on one bus. The driver treats the module as one logical device
with two connections; the compass heading is computed in software from both
sensors.

Default configuration (written at construction):
    - Accelerometer: ±2 g (1 mg/LSB), 100 Hz ODR, high-resolution mode,
      all axes enabled, BDU=1, high-pass filter bypassed, FIFO bypassed,
      all interrupt routing cleared
    - Magnetometer: ±1.3 gauss (±130 µT), 15 Hz ODR, continuous conversion,
      temperature sensor off

The two slaves use different I²C framing: the accelerometer sub-address bit 7
enables address auto-increment (the driver sets it for every multi-byte
transfer); the magnetometer has an address pointer that always auto-increments
(wrapping from 0x08 back to 0x03 and from 0x0C to 0x00) and needs no extra bit.
Magnetometer outputs are X, Z, Y with the high byte first — the driver reorders
to X, Y, Z. The accelerometer outputs are X, Y, Z low byte first.

Args:
    accel_connection: ``RegisterConnection`` (I²C or SMBus) bound to the
        accelerometer slave address 0x19.
    mag_connection: ``RegisterConnection`` (I²C or SMBus) bound to the
        magnetometer slave address 0x1E, on the same bus.
"""

import math
import struct

from periph.connection.register import to_signed

# Valid accelerometer output data rates in Hz (CTRL_REG1_A ODR bits 7:4).
_ACCEL_ODR_CODES = (
    (1, 0x1),
    (10, 0x2),
    (25, 0x3),
    (50, 0x4),
    (100, 0x5),
    (200, 0x6),
    (400, 0x7),
    (1620, 0x8),
    (1344, 0x9),
    (5376, 0x9),
)

# Sensitivity in mg/LSB, indexed [mode][range_bits]; mode 0 = low-power (8-bit),
# 1 = normal (10-bit), 2 = high-resolution (12-bit); range 0..3 = ±2/4/8/16 g.
_ACCEL_SENSITIVITY_MG = (
    (16.0, 32.0, 64.0, 192.0),
    (4.0, 8.0, 16.0, 48.0),
    (1.0, 2.0, 4.0, 12.0),
)

# Arithmetic shift per mode: 8-bit low-power → 8, 10-bit normal → 6, 12-bit HR → 4.
_ACCEL_SHIFT = (8, 6, 4)

# Magnetometer gain codes GN<2:0> 1..7: full scale in µT and LSB/gauss for X/Y and Z.
_MAG_RANGES = (
    (130, 1100.0, 980.0),
    (190, 855.0, 760.0),
    (250, 670.0, 600.0),
    (400, 450.0, 400.0),
    (470, 400.0, 355.0),
    (560, 330.0, 295.0),
    (810, 230.0, 205.0),
)

# Valid magnetometer output data rates in Hz (CRA_REG_M DO bits 4:2).
_MAG_ODR_CODES = (
    (0.75, 0x0),
    (1.5, 0x1),
    (3.0, 0x2),
    (7.5, 0x3),
    (15.0, 0x4),
    (30.0, 0x5),
    (75.0, 0x6),
    (220.0, 0x7),
)


def _delay_ms(ms):
    import time
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


class LSM303Minimal:
    """LSM303 accelerometer + magnetometer — minimal interface.

    Reads 3-axis acceleration in *g* and 3-axis magnetic flux density in µT
    with sensible defaults; no configuration is required beyond the two
    connections.

    Args:
        accel_connection: ``RegisterConnection`` (I²C or SMBus) bound to the
            accelerometer slave address 0x19.
        mag_connection: ``RegisterConnection`` (I²C or SMBus) bound to the
            magnetometer slave address 0x1E, on the same bus as
            ``accel_connection``.

    Raises:
        ValueError: If the magnetometer identity registers do not read
            0x48 0x34 0x33.
    """

    # Accelerometer register map (sub-address bit 7 = auto-increment).
    _REG_CTRL1_A      = 0x20
    _REG_CTRL2_A      = 0x21
    _REG_CTRL3_A      = 0x22
    _REG_CTRL4_A      = 0x23
    _REG_CTRL5_A      = 0x24
    _REG_CTRL6_A      = 0x25
    _REG_REFERENCE_A  = 0x26
    _REG_STATUS_A     = 0x27
    _REG_OUT_X_L_A    = 0x28
    _REG_FIFO_CTRL_A  = 0x2E
    _REG_FIFO_SRC_A   = 0x2F
    _REG_INT1_CFG_A   = 0x30
    _REG_INT1_SRC_A   = 0x31
    _REG_INT1_THS_A   = 0x32
    _REG_INT1_DUR_A   = 0x33
    _REG_INT2_CFG_A   = 0x34
    _REG_INT2_SRC_A   = 0x35
    _REG_INT2_THS_A   = 0x36
    _REG_INT2_DUR_A   = 0x37
    _REG_CLICK_CFG_A  = 0x38
    _REG_CLICK_SRC_A  = 0x39
    _REG_CLICK_THS_A  = 0x3A
    _REG_TIME_LIMIT_A = 0x3B
    _REG_TIME_LAT_A   = 0x3C
    _REG_TIME_WIN_A   = 0x3D

    # Magnetometer register map (address pointer auto-increments; no auto-inc bit).
    _REG_CRA_M     = 0x00
    _REG_CRB_M     = 0x01
    _REG_MR_M      = 0x02
    _REG_OUT_X_H_M = 0x03
    _REG_SR_M      = 0x09
    _REG_IRA_M     = 0x0A
    _REG_IRB_M     = 0x0B
    _REG_IRC_M     = 0x0C
    _REG_TEMP_H_M  = 0x31
    _REG_TEMP_L_M  = 0x32

    # Magnetometer identity: IRA/IRB/IRC = ASCII "H43"; identical to HMC5883L.
    _MAG_ID = b'\x48\x34\x33'

    def __init__(self, accel_connection, mag_connection):
        self._accel = accel_connection
        self._mag = mag_connection
        self._range_bits = 0        # 0..3: ±2/4/8/16 g
        self._mode = 2              # 0 low-power, 1 normal, 2 high-resolution
        self._odr_hz = 100.0        # accelerometer output data rate
        self._mag_gn = 0            # 0..6 index into _MAG_RANGES
        self._mag_odr_hz = 15.0
        self._temp_enabled = False

        # Boot download of the factory trim values (≥ 5 ms, as for sibling parts).
        _delay_ms(5)

        identity = self._mag.read_reg(self._REG_IRA_M, 3)
        if bytes(identity) != self._MAG_ID:
            raise ValueError('LSM303 magnetometer ID: expected 0x483433, got 0x{}'.format(
                bytes(identity).hex().upper()))

        # Accelerometer: no soft reset — write the control registers to known values.
        self._accel.write_reg(self._REG_CTRL2_A, 0x00)
        self._accel.write_reg(self._REG_CTRL3_A, 0x00)
        self._accel.write_reg(self._REG_CTRL5_A, 0x00)
        self._accel.write_reg(self._REG_CTRL6_A, 0x00)
        self._accel.write_reg(self._REG_FIFO_CTRL_A, 0x00)
        # BDU=1, FS=00 (±2 g), HR=1 (high-resolution).
        self._accel.write_reg(self._REG_CTRL4_A, 0x88)
        # ODR=0101 (100 Hz), LPen=0, all axes enabled — leaves power-down.
        self._accel.write_reg(self._REG_CTRL1_A, 0x57)

        # Magnetometer: the part resets into sleep mode, so write all three
        # control registers explicitly (15 Hz, ±1.3 gauss, continuous).
        self._mag.write_reg(self._REG_CRA_M, 0x10)
        self._mag.write_reg(self._REG_CRB_M, 0x20)
        self._mag.write_reg(self._REG_MR_M, 0x00)

        # Turn-on waits: 7/ODR (70 ms at 100 Hz) for the accelerometer and one
        # output period (67 ms at 15 Hz) for the first magnetometer sample.
        _delay_ms(70)
        _delay_ms(67)

    def _accel_sensitivity_g(self):
        """Sensitivity in g/LSB at the current mode and range."""
        return _ACCEL_SENSITIVITY_MG[self._mode][self._range_bits] / 1000.0

    def _convert_accel_sample(self, raw):
        """Convert a 6-byte accelerometer burst to (x, y, z) signed counts."""
        rx = to_signed(raw[0] | (raw[1] << 8), 16)
        ry = to_signed(raw[2] | (raw[3] << 8), 16)
        rz = to_signed(raw[4] | (raw[5] << 8), 16)
        shift = _ACCEL_SHIFT[self._mode]
        return (rx >> shift, ry >> shift, rz >> shift)

    def read_acceleration(self):
        """Read 3-axis linear acceleration.

        Burst-reads OUT_X_L_A..OUT_Z_H_A with the auto-increment bit set so
        all six bytes belong to one sample (BDU=1).

        Returns:
            tuple: (x, y, z) acceleration in *g*.
        """
        raw = self._accel.read_reg(self._REG_OUT_X_L_A | 0x80, 6)
        cx, cy, cz = self._convert_accel_sample(raw)
        scale = self._accel_sensitivity_g()
        return (cx * scale, cy * scale, cz * scale)

    def read_magnetic(self):
        """Read 3-axis magnetic flux density.

        Burst-reads the six output registers 0x03..0x08 (on the wire: X, Z, Y,
        each high byte first) in one transaction and reorders to X, Y, Z.

        Returns:
            tuple: (x, y, z) magnetic flux density in µT.
        """
        raw = self._mag.read_reg(self._REG_OUT_X_H_M, 6)
        mx = to_signed((raw[0] << 8) | raw[1], 16)
        mz = to_signed((raw[2] << 8) | raw[3], 16)
        my = to_signed((raw[4] << 8) | raw[5], 16)
        gain_xy = _MAG_RANGES[self._mag_gn][1]
        gain_z = _MAG_RANGES[self._mag_gn][2]
        return (mx / gain_xy * 100.0, my / gain_xy * 100.0, mz / gain_z * 100.0)


class LSM303Full(LSM303Minimal):
    """LSM303 full interface — extends LSM303Minimal with complete functionality.

    Adds accelerometer range / data-rate / power-mode configuration, per-axis
    enable, raw data access, the high-pass filter, the 32-level FIFO, two
    inertial interrupt generators with free-fall and 6D/4D orientation,
    single/double click detection, interrupt routing across INT1/INT2, the
    magnetometer gain / data-rate / operating-mode controls, the relative
    temperature sensor, the software compass heading, and memory reboot.

    Args:
        accel_connection: ``RegisterConnection`` (I²C or SMBus) bound to the
            accelerometer slave address 0x19.
        mag_connection: ``RegisterConnection`` (I²C or SMBus) bound to the
            magnetometer slave address 0x1E, on the same bus.
    """

    # Interrupt source bits — the poll_interrupt() status layout.
    SOURCE_DATA_READY     = 0x0001
    SOURCE_WATERMARK      = 0x0002
    SOURCE_OVERRUN        = 0x0004
    SOURCE_IA1            = 0x0008
    SOURCE_IA2            = 0x0010
    SOURCE_CLICK          = 0x0020
    SOURCE_BOOT           = 0x0040
    SOURCE_MAG_DATA_READY = 0x0080

    # CTRL_REG3_A routing bits (INT1 side).
    _I1_CLICK    = 0x80
    _I1_AOI1     = 0x40
    _I1_AOI2     = 0x20
    _I1_DRDY1    = 0x10
    _I1_WTM      = 0x04
    _I1_OVERRUN  = 0x01

    # CTRL_REG6_A routing bits (INT2 side).
    _I2_CLICK  = 0x80
    _I2_INT1   = 0x40
    _I2_INT2   = 0x20
    _BOOT_I1   = 0x10

    def __init__(self, accel_connection, mag_connection):
        LSM303Minimal.__init__(self, accel_connection, mag_connection)
        self._int_callbacks = {1: None, 2: None}
        self._int_sources = set()
        self._int_handler = None
        self._poll_stop = None

    # ------------------------------------------------------------------
    # Accelerometer configuration
    # ------------------------------------------------------------------

    def set_range(self, range_g):
        """Set the accelerometer full-scale range.

        Args:
            range_g: One of 2, 4, 8, 16 (*g*). Updates the cached sensitivity.
        """
        code = {2: 0, 4: 1, 8: 2, 16: 3}.get(range_g)
        if code is None:
            raise ValueError('range_g must be one of 2, 4, 8, 16')
        self._range_bits = code
        ctrl4 = self._accel.read_reg(self._REG_CTRL4_A, 1)[0]
        ctrl4 = (ctrl4 & 0xCF) | (code << 4)
        self._accel.write_reg(self._REG_CTRL4_A, ctrl4)

    def set_data_rate(self, rate_hz):
        """Set the accelerometer output data rate.

        Args:
            rate_hz: One of 0 (power-down), 1, 10, 25, 50, 100, 200, 400,
                1344, 1620 or 5376 Hz. 1620 Hz and 5376 Hz exist in low-power
                mode only, 1344 Hz in normal / high-resolution mode.

        Raises:
            ValueError: If the rate is not allowed in the current mode.
        """
        code = None
        for rate, c in _ACCEL_ODR_CODES:
            if rate == rate_hz:
                code = c
                break
        if code is None:
            raise ValueError('rate_hz must be one of 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376')
        if code == 0x8 and self._mode != 0:
            raise ValueError('1620 Hz requires low-power mode (set_mode(0))')
        if code == 0x9 and rate_hz == 5376 and self._mode != 0:
            raise ValueError('5376 Hz requires low-power mode (set_mode(0))')
        if code == 0x9 and rate_hz == 1344 and self._mode == 0:
            raise ValueError('1344 Hz requires normal or high-resolution mode')
        ctrl1 = self._accel.read_reg(self._REG_CTRL1_A, 1)[0]
        ctrl1 = (ctrl1 & 0x0F) | (code << 4)
        self._accel.write_reg(self._REG_CTRL1_A, ctrl1)
        self._odr_hz = float(rate_hz)

    def set_mode(self, mode):
        """Set the accelerometer operating mode (resolution).

        Args:
            mode: 0 = low-power (8-bit), 1 = normal (10-bit), 2 =
                high-resolution (12-bit). Updates the cached sensitivity.
        """
        if mode not in (0, 1, 2):
            raise ValueError('mode must be 0 (low-power), 1 (normal) or 2 (high-resolution)')
        ctrl1 = self._accel.read_reg(self._REG_CTRL1_A, 1)[0]
        ctrl4 = self._accel.read_reg(self._REG_CTRL4_A, 1)[0]
        if mode == 0:
            ctrl1 |= 0x08   # LPen = 1
            ctrl4 &= ~0x08  # HR = 0
        elif mode == 1:
            ctrl1 &= ~0x08
            ctrl4 &= ~0x08
        else:
            ctrl1 &= ~0x08
            ctrl4 |= 0x08
        self._accel.write_reg(self._REG_CTRL1_A, ctrl1)
        self._accel.write_reg(self._REG_CTRL4_A, ctrl4)
        self._mode = mode

    def set_axes(self, x=True, y=True, z=True):
        """Enable or disable the accelerometer axes.

        Args:
            x: Enable the X axis (default True).
            y: Enable the Y axis (default True).
            z: Enable the Z axis (default True).
        """
        ctrl1 = self._accel.read_reg(self._REG_CTRL1_A, 1)[0]
        ctrl1 = (ctrl1 & 0xF8) | ((1 if z else 0) << 2) | ((1 if y else 0) << 1) | (1 if x else 0)
        self._accel.write_reg(self._REG_CTRL1_A, ctrl1)

    def power_down(self):
        """Enter power-down (ODR = 0000). Register contents are retained."""
        ctrl1 = self._accel.read_reg(self._REG_CTRL1_A, 1)[0]
        self._accel.write_reg(self._REG_CTRL1_A, ctrl1 & 0x0F)
        self._odr_hz = 0.0

    def read_acceleration_raw(self):
        """Read raw accelerometer counts at the current resolution.

        Returns:
            tuple: (x, y, z) signed counts (12/10/8-bit right-aligned per mode).
        """
        raw = self._accel.read_reg(self._REG_OUT_X_L_A | 0x80, 6)
        return self._convert_accel_sample(raw)

    def new_data_available(self):
        """Check whether a new accelerometer sample is available.

        Returns:
            bool: True when STATUS_REG_A.ZYXDA is set.
        """
        return bool(self._accel.read_reg(self._REG_STATUS_A, 1)[0] & 0x08)

    def read_status(self):
        """Read the accelerometer status register.

        Returns:
            int: STATUS_REG_A byte (ZYXOR/ZOR/YOR/XOR overrun, ZYXDA/*DA data-ready).
        """
        return self._accel.read_reg(self._REG_STATUS_A, 1)[0]

    def set_high_pass(self, mode=0, cutoff=0, filter_output=False, on_ia1=False, on_ia2=False, on_click=False):
        """Configure the high-pass filter.

        Args:
            mode: HPM<1:0> — 0 normal (reset by reading REFERENCE_A), 1
                reference signal for filtering, 2 normal, 3 autoreset on
                interrupt event.
            cutoff: HPCF<2:1> raw code 0–3 (no Hz values tabulated for this part).
            filter_output: True = filtered data goes to the output registers and FIFO.
            on_ia1: True = high-pass filter enabled for interrupt generator 1.
            on_ia2: True = high-pass filter enabled for interrupt generator 2.
            on_click: True = high-pass filter enabled for click detection.
        """
        ctrl2 = ((mode & 0x03) << 6) | ((cutoff & 0x03) << 4)
        if filter_output:
            ctrl2 |= 0x08
        if on_click:
            ctrl2 |= 0x04
        if on_ia2:
            ctrl2 |= 0x02
        if on_ia1:
            ctrl2 |= 0x01
        self._accel.write_reg(self._REG_CTRL2_A, ctrl2)

    def reset_high_pass(self):
        """Reset the high-pass filter (HPM = 00) by reading REFERENCE_A.

        Returns:
            int: The REFERENCE_A register value.
        """
        return self._accel.read_reg(self._REG_REFERENCE_A, 1)[0]

    def set_reference(self, value):
        """Write the high-pass reference register (for HPM = 01).

        Args:
            value: Reference value 0–255.
        """
        self._accel.write_reg(self._REG_REFERENCE_A, value & 0xFF)

    # ------------------------------------------------------------------
    # FIFO
    # ------------------------------------------------------------------

    def set_fifo(self, mode, watermark=0, trigger_int2=False):
        """Configure the 32-level FIFO.

        Args:
            mode: 0 bypass, 1 FIFO, 2 stream, 3 trigger (stream-to-FIFO).
                Returning to bypass clears the buffer.
            watermark: Watermark level 0–31.
            trigger_int2: Trigger mode source — False = INT1, True = INT2.
        """
        fifo_ctrl = ((mode & 0x03) << 6) | (0x20 if trigger_int2 else 0x00) | (watermark & 0x1F)
        self._accel.write_reg(self._REG_FIFO_CTRL_A, fifo_ctrl)
        ctrl5 = self._accel.read_reg(self._REG_CTRL5_A, 1)[0]
        if mode in (1, 2, 3):
            ctrl5 |= 0x40
        else:
            ctrl5 &= ~0x40
        self._accel.write_reg(self._REG_CTRL5_A, ctrl5)

    def fifo_level(self):
        """Return the number of unread sample sets in the FIFO (0–32).

        Returns:
            int: FSS<4:0> from FIFO_SRC_REG_A.
        """
        return self._accel.read_reg(self._REG_FIFO_SRC_A, 1)[0] & 0x1F

    def read_fifo(self, count=None):
        """Drain up to ``count`` sample sets from the FIFO.

        Each sample is popped with one 6-byte burst read of OUT_X_L_A..OUT_Z_H_A.

        Args:
            count: Maximum number of sample sets to read (default: all
                unread samples reported by FSS).

        Returns:
            list: (x, y, z) tuples in *g*.
        """
        if count is None:
            count = self.fifo_level()
        out = []
        scale = self._accel_sensitivity_g()
        for _ in range(count):
            raw = self._accel.read_reg(self._REG_OUT_X_L_A | 0x80, 6)
            cx, cy, cz = self._convert_accel_sample(raw)
            out.append((cx * scale, cy * scale, cz * scale))
        return out

    def read_fifo_status(self):
        """Read the decoded FIFO status.

        Returns:
            tuple: (watermark, overrun, empty, level) — the first three are
            bools, ``level`` is the FSS<4:0> sample count.
        """
        src = self._accel.read_reg(self._REG_FIFO_SRC_A, 1)[0]
        return (bool(src & 0x80), bool(src & 0x40), bool(src & 0x20), src & 0x1F)

    # ------------------------------------------------------------------
    # Inertial interrupt generators and click detection
    # ------------------------------------------------------------------

    def _int_regs(self, generator):
        """Register triple (CFG, SRC, THS, DUR) for generator 1 or 2."""
        if generator == 1:
            return (self._REG_INT1_CFG_A, self._REG_INT1_SRC_A,
                    self._REG_INT1_THS_A, self._REG_INT1_DUR_A)
        if generator == 2:
            return (self._REG_INT2_CFG_A, self._REG_INT2_SRC_A,
                    self._REG_INT2_THS_A, self._REG_INT2_DUR_A)
        raise ValueError('generator must be 1 or 2')

    def _encode_threshold(self, threshold_g):
        """Convert a threshold in g to the 7-bit INTx_THS / CLICK_THS code."""
        code = int(round(threshold_g * 128.0 / {0: 2, 1: 4, 2: 8, 3: 16}[self._range_bits]))
        if code > 127:
            code = 127
        if code < 0:
            code = 0
        return code

    def _encode_duration(self, duration_ms, max_bits=7):
        """Convert a duration in ms to the 1/ODR code, clamped to the width."""
        code = int(round(duration_ms * self._odr_hz / 1000.0))
        limit = (1 << max_bits) - 1
        if code > limit:
            code = limit
        if code < 0:
            code = 0
        return code

    def set_motion_interrupt(self, generator, events, threshold_g, duration_ms=0, and_mode=False):
        """Configure one inertial interrupt generator.

        Args:
            generator: 1 or 2 (INT1 generator / INT2 generator).
            events: 6-bit event mask (bit 5 = Z high, 4 = Z low, 3 = Y high,
                2 = Y low, 1 = X high, 0 = X low; the same bits select the
                direction zones in 6D modes).
            threshold_g: Event threshold in *g* (1 LSb = full-scale / 128).
            duration_ms: Minimum event duration in ms (1 LSb = 1/ODR).
            and_mode: True = AND combination of the enabled events, False = OR.
        """
        if generator not in (1, 2):
            raise ValueError('generator must be 1 or 2')
        cfg, _src, ths, dur = self._int_regs(generator)
        self._accel.write_reg(cfg, (0x80 if and_mode else 0x00) | (events & 0x3F))
        self._accel.write_reg(ths, self._encode_threshold(threshold_g))
        self._accel.write_reg(dur, self._encode_duration(duration_ms))

    def set_free_fall(self, generator=1, threshold_g=0.35, duration_ms=30):
        """Configure free-fall detection on one generator.

        All three axes' low events, AND-combined (INTx_CFG_A = 0x95).

        Args:
            generator: 1 (default) or 2.
            threshold_g: Free-fall threshold in *g*.
            duration_ms: Minimum free-fall duration in ms.
        """
        self.set_motion_interrupt(generator, 0x15, threshold_g, duration_ms, and_mode=True)

    def set_orientation(self, generator=1, position=False, four_d=False):
        """Configure 6D / 4D orientation recognition on one generator.

        Args:
            generator: 1 (default) or 2.
            position: False = 6D movement recognition (one-ODR pulse when the
                orientation moves into a known zone), True = 6D position
                recognition (stays active inside a known zone).
            four_d: True = 4D detection (Z-axis zone disabled; needs the 6D bit).
        """
        if generator not in (1, 2):
            raise ValueError('generator must be 1 or 2')
        cfg, _src, _ths, _dur = self._int_regs(generator)
        value = 0x40 | 0x3F  # 6D + all six direction zones
        if position:
            value |= 0x80
        self._accel.write_reg(cfg, value)
        ctrl5 = self._accel.read_reg(self._REG_CTRL5_A, 1)[0]
        if four_d:
            ctrl5 |= (0x04 if generator == 1 else 0x01)
        else:
            ctrl5 &= ~(0x04 if generator == 1 else 0x01)
        self._accel.write_reg(self._REG_CTRL5_A, ctrl5)

    def set_latch(self, generator, enabled):
        """Latch (or unlatch) one generator's interrupt until its source is read.

        Args:
            generator: 1 or 2.
            enabled: True = latch INT output until INTx_SOURCE_A is read.
        """
        if generator not in (1, 2):
            raise ValueError('generator must be 1 or 2')
        ctrl5 = self._accel.read_reg(self._REG_CTRL5_A, 1)[0]
        if enabled:
            ctrl5 |= (0x08 if generator == 1 else 0x02)
        else:
            ctrl5 &= ~(0x08 if generator == 1 else 0x02)
        self._accel.write_reg(self._REG_CTRL5_A, ctrl5)

    def read_interrupt_source(self, generator):
        """Read one generator's source register — clears the interrupt.

        Args:
            generator: 1 or 2.

        Returns:
            int: INTx_SOURCE_A byte (bit 6 = IA, bits 5:0 = axis high/low flags).
        """
        _cfg, src, _ths, _dur = self._int_regs(generator)
        return self._accel.read_reg(src, 1)[0]

    def set_click(self, single=0, double=0, threshold_g=0.5, time_limit_ms=10, latency_ms=20, window_ms=50):
        """Configure single / double click detection.

        Args:
            single: Axis mask for single click (X=1, Y=2, Z=4; 0 = off).
            double: Axis mask for double click (X=1, Y=2, Z=4; 0 = off).
            threshold_g: Click threshold in *g* (1 LSb = full-scale / 128).
            time_limit_ms: Maximum click duration in ms (1 LSb = 1/ODR, 7-bit).
            latency_ms: Time after a click in which the click interrupt is not
                re-armed (1 LSb = 1/ODR, 8-bit).
            window_ms: Time window for a second click (1 LSb = 1/ODR, 8-bit).
        """
        if single not in range(0, 8) or double not in range(0, 8):
            raise ValueError('single / double are axis masks: X=1, Y=2, Z=4')
        # Axis masks X=1/Y=2/Z=4 spread to CLICK_CFG_A bits: XD=0, XS=1, YD=2, YS=3, ZD=4, ZS=5.
        single_code = ((single & 1) << 1) | ((single & 2) << 2) | ((single & 4) << 3)
        double_code = ((double & 1) << 0) | ((double & 2) << 1) | ((double & 4) << 2)
        self._accel.write_reg(self._REG_CLICK_CFG_A, single_code | double_code)
        self._accel.write_reg(self._REG_CLICK_THS_A, self._encode_threshold(threshold_g))
        self._accel.write_reg(self._REG_TIME_LIMIT_A, self._encode_duration(time_limit_ms))
        self._accel.write_reg(self._REG_TIME_LAT_A, self._encode_duration(latency_ms, max_bits=8))
        self._accel.write_reg(self._REG_TIME_WIN_A, self._encode_duration(window_ms, max_bits=8))

    def read_click_source(self):
        """Read the click source register — clears the click interrupt.

        Returns:
            int: CLICK_SRC_A byte (bit 6 = IA, bit 5 = DCLICK, bit 4 = SCLICK,
            bit 3 = sign, bits 2:0 = Z/Y/X event axis).
        """
        return self._accel.read_reg(self._REG_CLICK_SRC_A, 1)[0]

    def set_interrupt_polarity(self, active_low):
        """Set the INT1 / INT2 output polarity.

        Args:
            active_low: True = INT pins active-low, False = active-high (default).
        """
        ctrl6 = self._accel.read_reg(self._REG_CTRL6_A, 1)[0]
        if active_low:
            ctrl6 |= 0x02
        else:
            ctrl6 &= ~0x02
        self._accel.write_reg(self._REG_CTRL6_A, ctrl6)

    # ------------------------------------------------------------------
    # Magnetometer configuration
    # ------------------------------------------------------------------

    def set_mag_range(self, range_ut):
        """Set the magnetometer full-scale range (gain).

        Args:
            range_ut: One of 130, 190, 250, 400, 470, 560, 810 (µT).
                Updates the cached conversion gains.
        """
        index = {130: 0, 190: 1, 250: 2, 400: 3, 470: 4, 560: 5, 810: 6}.get(range_ut)
        if index is None:
            raise ValueError('range_ut must be one of 130, 190, 250, 400, 470, 560, 810')
        self._mag_gn = index
        self._mag.write_reg(self._REG_CRB_M, (index + 1) << 5)

    def set_mag_data_rate(self, rate_hz):
        """Set the magnetometer output data rate.

        Args:
            rate_hz: One of 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220 Hz.
                Preserves the TEMP_EN setting.
        """
        code = None
        for rate, c in _MAG_ODR_CODES:
            if rate == rate_hz:
                code = c
                break
        if code is None:
            raise ValueError('rate_hz must be one of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220')
        cra = self._mag.read_reg(self._REG_CRA_M, 1)[0]
        self._mag.write_reg(self._REG_CRA_M, (cra & 0x80) | (code << 2))
        self._mag_odr_hz = float(rate_hz)

    def set_mag_mode(self, mode):
        """Set the magnetometer operating mode.

        Args:
            mode: 0 continuous conversion, 1 single conversion (one
                conversion, then back to sleep), 2 sleep.
        """
        if mode not in (0, 1, 2):
            raise ValueError('mode must be 0 (continuous), 1 (single) or 2 (sleep)')
        self._mag.write_reg(self._REG_MR_M, mode)

    def read_magnetic_raw(self):
        """Read raw magnetometer counts (X, Y, Z order, after reordering).

        Returns:
            tuple: (x, y, z) signed 12-bit counts.
        """
        raw = self._mag.read_reg(self._REG_OUT_X_H_M, 6)
        mx = to_signed((raw[0] << 8) | raw[1], 16)
        mz = to_signed((raw[2] << 8) | raw[3], 16)
        my = to_signed((raw[4] << 8) | raw[5], 16)
        return (mx, my, mz)

    def mag_data_ready(self):
        """Check whether a new magnetometer sample is available.

        Returns:
            bool: True when SR_REG_M.DRDY is set.
        """
        return bool(self._mag.read_reg(self._REG_SR_M, 1)[0] & 0x01)

    def read_mag_status(self):
        """Read the decoded magnetometer status register.

        Returns:
            tuple: (drdy, lock) — DRDY = new set of measurements available,
            LOCK = output registers locked mid-read.
        """
        sr = self._mag.read_reg(self._REG_SR_M, 1)[0]
        return (bool(sr & 0x01), bool(sr & 0x02))

    def read_mag_identity(self):
        """Read the magnetometer identification registers.

        Returns:
            bytes: IRA/IRB/IRC (0x48 0x34 0x33 — identical to HMC5883L).
        """
        return bytes(self._mag.read_reg(self._REG_IRA_M, 3))

    def enable_temperature(self, enabled):
        """Enable or disable the relative temperature sensor.

        Args:
            enabled: True = TEMP_EN set (updates at the magnetometer data rate).
        """
        cra = self._mag.read_reg(self._REG_CRA_M, 1)[0]
        if enabled:
            cra |= 0x80
        else:
            cra &= ~0x80
        self._mag.write_reg(self._REG_CRA_M, cra)
        self._temp_enabled = enabled

    def read_temperature_delta(self):
        """Read the relative temperature.

        The sensor is relative only — the datasheet gives no zero-degree
        reference — so the value is a delta, never an absolute °C. Enables
        TEMP_EN on first use (read-modify-write).

        Returns:
            float: Relative temperature in °C (8 LSB/°C).
        """
        if not self._temp_enabled:
            self.enable_temperature(True)
        raw = self._mag.read_reg(self._REG_TEMP_H_M, 2)
        value = to_signed((raw[0] << 8) | raw[1], 16) >> 4
        return value / 8.0

    # ------------------------------------------------------------------
    # Software compass
    # ------------------------------------------------------------------

    def heading(self, tilt_compensate=True):
        """Compute the compass heading from both sensors.

        Uses the ``atan2(y, x)`` convention, wrapped to 0–360°. The datasheet
        documents neither the axis alignment between the two dies nor a
        declination, so the result assumes both sensors report in the same
        body frame and is magnetic (not true) north.

        Args:
            tilt_compensate: True (default) = project the magnetic field onto
                the horizontal plane using the current acceleration; False =
                flat compass from the magnetic X/Y components only.

        Returns:
            float: Heading in degrees, 0–360.
        """
        mx, my, mz = self.read_magnetic()
        if not tilt_compensate:
            angle = math.degrees(math.atan2(my, mx))
        else:
            ax, ay, az = self.read_acceleration()
            norm = math.sqrt(ax * ax + ay * ay + az * az)
            if norm == 0.0:
                norm = 1.0
            ax, ay, az = ax / norm, ay / norm, az / norm
            roll = math.atan2(ay, az)
            pitch = math.atan2(-ax, ay * math.sin(roll) + az * math.cos(roll))
            xh = mx * math.cos(pitch) + mz * math.sin(pitch)
            yh = (mx * math.sin(roll) * math.sin(pitch) + my * math.cos(roll)
                  - mz * math.sin(roll) * math.cos(pitch))
            angle = math.degrees(math.atan2(yh, xh))
        return angle if angle >= 0.0 else angle + 360.0

    # ------------------------------------------------------------------
    # Interrupts (Level 3: INT1 / INT2 routing)
    # ------------------------------------------------------------------

    def enable_interrupt(self, source, pin=1):
        """Route one interrupt source to INT1 or INT2.

        Only routes the source; the generator or function behind it is
        configured with the ``set_motion_interrupt`` / ``set_click`` / ...
        methods. ``pin`` is ignored for INT1-only / INT2-only sources.
        ``SOURCE_MAG_DATA_READY`` is rejected (it is always on the dedicated
        magnetometer DRDY pin and is not routable).

        Args:
            source: One of the ``SOURCE_*`` constants.
            pin: 1 (default) or 2.
        """
        if source == self.SOURCE_MAG_DATA_READY:
            raise ValueError('SOURCE_MAG_DATA_READY is on the magnetometer DRDY pin and is not routable')
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        ctrl3 = self._accel.read_reg(self._REG_CTRL3_A, 1)[0]
        ctrl6 = self._accel.read_reg(self._REG_CTRL6_A, 1)[0]
        if source == self.SOURCE_DATA_READY:
            ctrl3 |= self._I1_DRDY1
        elif source == self.SOURCE_WATERMARK:
            ctrl3 |= self._I1_WTM
        elif source == self.SOURCE_OVERRUN:
            ctrl3 |= self._I1_OVERRUN
        elif source == self.SOURCE_IA1:
            if pin == 1:
                ctrl3 |= self._I1_AOI1
            else:
                ctrl6 |= self._I2_INT1
        elif source == self.SOURCE_IA2:
            if pin == 1:
                ctrl3 |= self._I1_AOI2
            else:
                ctrl6 |= self._I2_INT2
        elif source == self.SOURCE_CLICK:
            if pin == 1:
                ctrl3 |= self._I1_CLICK
            else:
                ctrl6 |= self._I2_CLICK
        elif source == self.SOURCE_BOOT:
            if pin != 2:
                raise ValueError('SOURCE_BOOT is INT2-only')
            ctrl6 |= self._BOOT_I1
        else:
            raise ValueError('unknown interrupt source 0x{:X}'.format(source))
        self._accel.write_reg(self._REG_CTRL3_A, ctrl3)
        self._accel.write_reg(self._REG_CTRL6_A, ctrl6)
        self._int_sources.add(source)

    def disable_interrupt(self, source, pin=1):
        """Remove one interrupt source from INT1 or INT2.

        Args:
            source: One of the ``SOURCE_*`` constants.
            pin: 1 (default) or 2.
        """
        if source == self.SOURCE_MAG_DATA_READY:
            raise ValueError('SOURCE_MAG_DATA_READY is on the magnetometer DRDY pin and is not routable')
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        ctrl3 = self._accel.read_reg(self._REG_CTRL3_A, 1)[0]
        ctrl6 = self._accel.read_reg(self._REG_CTRL6_A, 1)[0]
        if source == self.SOURCE_DATA_READY:
            ctrl3 &= ~self._I1_DRDY1
        elif source == self.SOURCE_WATERMARK:
            ctrl3 &= ~self._I1_WTM
        elif source == self.SOURCE_OVERRUN:
            ctrl3 &= ~self._I1_OVERRUN
        elif source == self.SOURCE_IA1:
            if pin == 1:
                ctrl3 &= ~self._I1_AOI1
            else:
                ctrl6 &= ~self._I2_INT1
        elif source == self.SOURCE_IA2:
            if pin == 1:
                ctrl3 &= ~self._I1_AOI2
            else:
                ctrl6 &= ~self._I2_INT2
        elif source == self.SOURCE_CLICK:
            if pin == 1:
                ctrl3 &= ~self._I1_CLICK
            else:
                ctrl6 &= ~self._I2_CLICK
        elif source == self.SOURCE_BOOT:
            if pin != 2:
                raise ValueError('SOURCE_BOOT is INT2-only')
            ctrl6 &= ~self._BOOT_I1
        else:
            raise ValueError('unknown interrupt source 0x{:X}'.format(source))
        self._accel.write_reg(self._REG_CTRL3_A, ctrl3)
        self._accel.write_reg(self._REG_CTRL6_A, ctrl6)
        self._int_sources.discard(source)

    def poll_interrupt(self):
        """Read the source registers of every enabled interrupt source.

        Reading INTx_SOURCE_A / CLICK_SRC_A clears their latched flags, so a
        poll acknowledges the INT line.

        Returns:
            int: Combined status — bit 0 SOURCE_DATA_READY, bit 1
            SOURCE_WATERMARK, bit 2 SOURCE_OVERRUN, bit 3 SOURCE_IA1, bit 4
            SOURCE_IA2, bit 5 SOURCE_CLICK, bit 7 SOURCE_MAG_DATA_READY.
            SOURCE_BOOT is pin-only and never reported.
        """
        status = 0
        if self.SOURCE_DATA_READY in self._int_sources:
            if self._accel.read_reg(self._REG_STATUS_A, 1)[0] & 0x08:
                status |= self.SOURCE_DATA_READY
        if self.SOURCE_WATERMARK in self._int_sources or self.SOURCE_OVERRUN in self._int_sources:
            fifo_src = self._accel.read_reg(self._REG_FIFO_SRC_A, 1)[0]
            if fifo_src & 0x80:
                status |= self.SOURCE_WATERMARK
            if fifo_src & 0x40:
                status |= self.SOURCE_OVERRUN
        if self.SOURCE_IA1 in self._int_sources:
            if self._accel.read_reg(self._REG_INT1_SRC_A, 1)[0] & 0x40:
                status |= self.SOURCE_IA1
        if self.SOURCE_IA2 in self._int_sources:
            if self._accel.read_reg(self._REG_INT2_SRC_A, 1)[0] & 0x40:
                status |= self.SOURCE_IA2
        if self.SOURCE_CLICK in self._int_sources:
            if self._accel.read_reg(self._REG_CLICK_SRC_A, 1)[0] & 0x40:
                status |= self.SOURCE_CLICK
        if self._mag is not None:
            if self._mag.read_reg(self._REG_SR_M, 1)[0] & 0x01:
                status |= self.SOURCE_MAG_DATA_READY
        return status

    def on_interrupt(self, callback, pin=1):
        """Subscribe to INT1 or INT2 assertions.

        Uses the accelerometer connection's ``int_pin`` when wired (both INT
        lines typically share one edge input); falls back to a 5 ms polling
        thread otherwise. The callback receives the combined status int from
        ``poll_interrupt()``.

        Args:
            callback: Function called with the status int.
            pin: 1 (default) or 2.
        """
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        self._int_callbacks[pin] = callback
        self._start_int_delivery()

    def off_interrupt(self, pin=1):
        """Unsubscribe from INT1 or INT2 assertions.

        Args:
            pin: 1 (default) or 2.
        """
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        self._int_callbacks[pin] = None
        if self._int_callbacks[1] is None and self._int_callbacks[2] is None:
            self._stop_int_delivery()

    def _dispatch_int(self):
        """Poll the source registers and dispatch to any active callback."""
        callbacks = [cb for cb in self._int_callbacks.values() if cb is not None]
        if not callbacks:
            return
        try:
            status = self.poll_interrupt()
        except Exception:
            return
        for cb in callbacks:
            cb(status)

    def _start_int_delivery(self):
        """Deliver interrupts via the connection's INT pin, or a 5 ms poll thread."""
        int_pin = getattr(self._accel, 'int_pin', None)
        if int_pin is not None and hasattr(int_pin, 'on_edge'):
            if self._int_handler is None:
                self._int_handler = lambda pin: self._dispatch_int()
                int_pin.on_edge(self._int_handler, getattr(int_pin, 'FALLING', 0))
            return
        if self._poll_stop is not None:
            return
        try:
            import _thread
            import time as _time
        except ImportError:
            return
        stop = [False]

        def _loop():
            while not stop[0]:
                self._dispatch_int()
                if hasattr(_time, 'sleep_ms'):
                    _time.sleep_ms(5)
                else:
                    _time.sleep(0.005)

        self._poll_stop = stop
        _thread.start_new_thread(_loop, ())

    def _stop_int_delivery(self):
        """Stop edge delivery or the polling fallback thread."""
        int_pin = getattr(self._accel, 'int_pin', None)
        handler = self._int_handler
        if int_pin is not None and handler is not None and hasattr(int_pin, 'off_edge'):
            int_pin.off_edge(handler)
        self._int_handler = None
        if self._poll_stop is not None:
            self._poll_stop[0] = True
            self._poll_stop = None

    # ------------------------------------------------------------------
    # Reboot
    # ------------------------------------------------------------------

    def reboot(self):
        """Reboot the accelerometer memory content (BOOT = 1).

        Reloads the factory trim values; waits ≥ 5 ms. The datasheet does not
        say whether the control registers survive, so re-run the
        initialisation (construct a fresh driver) afterwards.
        """
        ctrl5 = self._accel.read_reg(self._REG_CTRL5_A, 1)[0]
        self._accel.write_reg(self._REG_CTRL5_A, ctrl5 | 0x80)
        _delay_ms(5)
