"""LSM303 — 3-axis accelerometer + 3-axis magnetometer e-compass (ST).

The LSM303DLHC is a system-in-package with a three-axis MEMS accelerometer
and a three-axis magnetometer behind two independent I²C slave addresses on
one bus. This driver treats the module as one logical device with two
connections, the same pattern as the MPU-9250's magnetometer.

The accelerometer (0x19) has four full-scale ranges (±2, ±4, ±8, ±16 *g*),
16-bit left-justified output, output data rates from 1 Hz to 5.376 kHz, a
32-level FIFO, a selectable high-pass filter, two inertial interrupt
generators (threshold, free-fall, 6D/4D orientation) and single/double-click
detection. After the slave address the master sends an 8-bit sub-address:
the 7 LSbs are the register address and the MSb enables address
auto-increment, so every multi-byte accelerometer transfer ORs 0x80 into the
register address.

The magnetometer (0x1E) has seven gain settings (±1.3 to ±8.1 gauss), 12-bit
output, data rates from 0.75 Hz to 220 Hz and a relative-only temperature
sensor. Its address pointer always auto-increments and needs no special bit;
the output order on the wire is X, Z, Y with the high byte first.

The driver is platform-agnostic — it only calls the connection interface.
The same file is used on MicroPython, CircuitPython and Linux hosts.
"""

import math

from periph.connection.register import to_signed

# Accelerometer full-scale ranges in g.
_RANGES = (2, 4, 8, 16)

# Operating modes: 0 = low-power (8-bit), 1 = normal (10-bit),
# 2 = high-resolution (12-bit).
_MODE_LP = 0
_MODE_NORMAL = 1
_MODE_HR = 2

# Accelerometer output data rates in Hz and their CTRL_REG1 ODR<3:0> codes.
_RATE_CODES = (
    (0, 0x00),      # power-down
    (1, 0x01),
    (10, 0x02),
    (25, 0x03),
    (50, 0x04),
    (100, 0x05),
    (200, 0x06),
    (400, 0x07),
    (1620, 0x08),   # low-power mode only
    (1344, 0x09),   # normal / high-resolution mode only
    (5376, 0x09),   # low-power mode only (same code as 1344 Hz)
)

_LP_ONLY_RATES = (1620, 5376)
_NON_LP_ONLY_RATES = (1344,)

# Sensitivity in mg/LSB by mode and full-scale range. The high-resolution
# row is from the datasheet; the normal / low-power rows are derived.
_SENSITIVITY_MG = {
    _MODE_LP:     {2: 16, 4: 32, 8: 64, 16: 192},
    _MODE_NORMAL: {2: 4, 4: 8, 8: 16, 16: 48},
    _MODE_HR:     {2: 1, 4: 2, 8: 4, 16: 12},
}

# Right-shift applied to the left-justified 16-bit accelerometer output pair.
_SHIFT = {_MODE_LP: 8, _MODE_NORMAL: 6, _MODE_HR: 4}

# Magnetometer gain / full scale by GN<2:0> code. Values are (X/Y LSB per
# gauss, Z LSB per gauss); each entry also carries the full-scale value in uT.
_MAG_GAIN = {
    0x20: (1100, 980, 130),   # GN = 001, ±1.3 gauss
    0x40: (855, 760, 190),    # GN = 010, ±1.9 gauss
    0x60: (670, 600, 250),    # GN = 011, ±2.5 gauss
    0x80: (450, 400, 400),    # GN = 100, ±4.0 gauss
    0xA0: (400, 355, 470),    # GN = 101, ±4.7 gauss
    0xC0: (330, 295, 560),    # GN = 110, ±5.6 gauss
    0xE0: (230, 205, 810),    # GN = 111, ±8.1 gauss
}

# CRB_REG_M value written for a requested full scale in uT.
_MAG_RANGE_CODE = {}
for _code, _entry in _MAG_GAIN.items():
    _MAG_RANGE_CODE[_entry[2]] = _code

# Magnetometer output data rates in Hz and their CRA_REG_M DO<2:0> codes.
_MAG_RATE_CODES = (
    (0.75, 0x00),
    (1.5, 0x04),
    (3.0, 0x08),
    (7.5, 0x0C),
    (15.0, 0x10),
    (30.0, 0x14),
    (75.0, 0x18),
    (220.0, 0x1C),
)

# Defaults baked into the Minimal driver.
_DEFAULT_RANGE_G = 2
_DEFAULT_RATE_HZ = 100
_DEFAULT_MODE = _MODE_HR
_DEFAULT_MAG_RANGE_UT = 130
_DEFAULT_MAG_RATE_HZ = 15.0


def _delay_ms(ms):
    """Sleep for ``ms`` milliseconds on every supported Python target.

    Args:
        ms: Number of milliseconds to sleep; 0 or less returns immediately.
    """
    if ms <= 0:
        return
    import time
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(int(ms))
    else:
        time.sleep(ms / 1000.0)


def _round(value):
    """Round half away from zero.

    Args:
        value: Number to round.

    Returns:
        int: The rounded value.
    """
    if value >= 0:
        return int(math.floor(value + 0.5))
    return -int(math.floor(-value + 0.5))


def _ceil_ms(value):
    """Round a sub-millisecond delay up to a whole millisecond.

    Args:
        value: Delay in milliseconds.

    Returns:
        int: The delay rounded up, 0 for non-positive values.
    """
    return int(math.ceil(value)) if value > 0 else 0


class LSM303Minimal:
    """LSM303 accelerometer + magnetometer — minimal interface.

    Reads X, Y, Z acceleration in *g* and magnetic flux density in µT with
    sensible defaults; no configuration is required beyond the two
    connections.

    The constructor runs the datasheet Initialization Sequence: it verifies
    the magnetometer identity, puts the accelerometer control registers in a
    known state (neither slave has a soft reset), configures ±2 *g* / 100 Hz
    / high-resolution on the accelerometer and ±130 µT / 15 Hz / continuous
    on the magnetometer, then waits for the first samples.

    Default configuration (baked in at construction):
        - Accelerometer ±2 *g* (1 mg/LSB), 100 Hz, high-resolution 12-bit
          mode, all axes enabled, ``BDU`` = 1, high-pass bypassed, FIFO
          bypassed, all interrupt routing cleared
        - Magnetometer ±130 µT, 15 Hz, continuous conversion, temperature
          sensor off

    Args:
        accel_connection: Configured ``RegisterConnection`` (I²C) bound to
            the accelerometer address 0x19.
        mag_connection: Configured ``RegisterConnection`` (I²C) bound to the
            magnetometer address 0x1E on the same bus.

    Raises:
        ValueError: If the magnetometer identity is not 0x48 0x34 0x33.
    """

    # Accelerometer registers.
    _REG_CTRL_REG1    = 0x20
    _REG_CTRL_REG2    = 0x21
    _REG_CTRL_REG3    = 0x22
    _REG_CTRL_REG4    = 0x23
    _REG_CTRL_REG5    = 0x24
    _REG_CTRL_REG6    = 0x25
    _REG_REFERENCE    = 0x26
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
    _REG_INT1_THS     = 0x32
    _REG_INT1_DUR     = 0x33
    _REG_INT2_CFG     = 0x34
    _REG_INT2_SRC     = 0x35
    _REG_INT2_THS     = 0x36
    _REG_INT2_DUR     = 0x37
    _REG_CLICK_CFG    = 0x38
    _REG_CLICK_SRC    = 0x39
    _REG_CLICK_THS    = 0x3A
    _REG_TIME_LIMIT   = 0x3B
    _REG_TIME_LATENCY = 0x3C
    _REG_TIME_WINDOW  = 0x3D

    # Magnetometer registers.
    _MAG_REG_CRA      = 0x00
    _MAG_REG_CRB      = 0x01
    _MAG_REG_MR       = 0x02
    _MAG_REG_OUT_X_H  = 0x03
    _MAG_REG_OUT_X_L  = 0x04
    _MAG_REG_OUT_Z_H  = 0x05
    _MAG_REG_OUT_Z_L  = 0x06
    _MAG_REG_OUT_Y_H  = 0x07
    _MAG_REG_OUT_Y_L  = 0x08
    _MAG_REG_SR       = 0x09
    _MAG_REG_IRA      = 0x0A
    _MAG_REG_IRB      = 0x0B
    _MAG_REG_IRC      = 0x0C
    _MAG_REG_TEMP_H   = 0x31
    _MAG_REG_TEMP_L   = 0x32

    _MAG_ID = b'\x48\x34\x33'

    # Control-register defaults of the Initialization Sequence.
    _CTRL_REG4_DEFAULT = 0x88   # BDU=1, FS=00 (±2 g), HR=1
    _CTRL_REG1_DEFAULT = 0x57   # ODR=0101 (100 Hz), LPen=0, X/Y/Z enabled

    def __init__(self, accel_connection, mag_connection):
        """Construct the driver and run the Initialization Sequence.

        Args:
            accel_connection: ``RegisterConnection`` bound to 0x19.
            mag_connection: ``RegisterConnection`` bound to 0x1E.

        Raises:
            ValueError: If the magnetometer identity is wrong.
        """
        self._accel = accel_connection
        self._mag = mag_connection
        self._range_g = _DEFAULT_RANGE_G
        self._mode = _DEFAULT_MODE
        self._rate_hz = _DEFAULT_RATE_HZ
        self._mag_gain_code = _MAG_RANGE_CODE[_DEFAULT_MAG_RANGE_UT]
        self._mag_rate_hz = _DEFAULT_MAG_RATE_HZ
        self._run_init_sequence()

    # ---- register helpers ------------------------------------------------

    def _write_reg(self, reg, value):
        """Write one accelerometer register.

        Args:
            reg: Register address.
            value: Value to write.
        """
        self._accel.write_reg(reg, value & 0xFF)

    def _read_reg(self, reg):
        """Read one accelerometer register.

        Args:
            reg: Register address.

        Returns:
            int: The register value.
        """
        return self._accel.read_reg(reg, 1)[0]

    def _read_burst(self, reg, length):
        """Read consecutive accelerometer registers.

        Multi-byte transfers set the sub-address MSb (auto-increment).

        Args:
            reg: First register address.
            length: Number of bytes to read.

        Returns:
            bytes: The register values.
        """
        sub = reg | 0x80 if length > 1 else reg
        return self._accel.read_reg(sub, length)

    def _mag_write_reg(self, reg, value):
        """Write one magnetometer register.

        Args:
            reg: Register address.
            value: Value to write.
        """
        self._mag.write_reg(reg, value & 0xFF)

    def _mag_read_reg(self, reg):
        """Read one magnetometer register.

        Args:
            reg: Register address.

        Returns:
            int: The register value.
        """
        return self._mag.read_reg(reg, 1)[0]

    def _mag_read_burst(self, reg, length):
        """Read consecutive magnetometer registers (no auto-increment bit).

        Args:
            reg: First register address.
            length: Number of bytes to read.

        Returns:
            bytes: The register values.
        """
        return self._mag.read_reg(reg, length)

    # ---- initialisation --------------------------------------------------

    def _run_init_sequence(self):
        """Run the datasheet Initialization Sequence."""
        # Step 1: power-up boot time.
        _delay_ms(5)

        # Step 2: magnetometer identity.
        identity = self._mag_read_burst(self._MAG_REG_IRA, 3)
        if bytes(identity) != self._MAG_ID:
            raise ValueError('LSM303 magnetometer identity: expected 48 34 33, got {}'.format(
                ' '.join('{:02X}'.format(b) for b in identity)))

        # Step 3: known accelerometer control-register state.
        self._write_reg(self._REG_CTRL_REG2, 0x00)
        self._write_reg(self._REG_CTRL_REG3, 0x00)
        self._write_reg(self._REG_CTRL_REG5, 0x00)
        self._write_reg(self._REG_CTRL_REG6, 0x00)
        self._write_reg(self._REG_FIFO_CTRL, 0x00)

        # Step 4: BDU, ±2 g, high-resolution.
        self._write_reg(self._REG_CTRL_REG4, self._CTRL_REG4_DEFAULT)

        # Step 5: leave power-down — this starts conversions.
        self._write_reg(self._REG_CTRL_REG1, self._CTRL_REG1_DEFAULT)

        # Step 6: magnetometer 15 Hz, ±130 uT, continuous.
        self._mag_write_reg(self._MAG_REG_CRA, 0x10)
        self._mag_write_reg(self._MAG_REG_CRB, self._mag_gain_code)
        self._mag_write_reg(self._MAG_REG_MR, 0x00)

        # Step 7: wait for the first samples.
        _delay_ms(max(self._settle_ms(), _ceil_ms(1000.0 / self._mag_rate_hz)))

    # ---- conversion helpers ---------------------------------------------

    def _settle_ms(self):
        """Milliseconds to wait after an ODR / mode change.

        Returns:
            int: The longer of the mode's turn-on time and one ODR period.
        """
        if self._rate_hz <= 0:
            return 0
        period_ms = 1000.0 / self._rate_hz
        if self._mode == _MODE_LP:
            turn_on_ms = 1.0
        elif self._mode == _MODE_NORMAL:
            turn_on_ms = 1.6
        else:
            turn_on_ms = 7.0 * period_ms
        return _ceil_ms(max(turn_on_ms, period_ms))

    def _decode_axis(self, low, high):
        """Decode one left-justified accelerometer output pair.

        Args:
            low: Low byte.
            high: High byte.

        Returns:
            int: Signed count at the current resolution.
        """
        raw16 = to_signed(low | (high << 8), 16)
        return raw16 >> _SHIFT[self._mode]

    def _mag_gains(self):
        """Current magnetometer (XY gain, Z gain) in LSB/gauss.

        Returns:
            tuple: (xy, z) gain.
        """
        gains = _MAG_GAIN[self._mag_gain_code]
        return gains[0], gains[1]

    def _decode_mag_axis(self, high, low, gain):
        """Decode one magnetometer output pair to µT.

        Args:
            high: High byte (sent first on the wire).
            low: Low byte.
            gain: LSB per gauss for the axis.

        Returns:
            float: Flux density in µT.
        """
        raw = to_signed((high << 8) | low, 16)
        return raw / float(gain) * 100.0

    # ---- minimal API -----------------------------------------------------

    def read_acceleration(self):
        """Read 3-axis linear acceleration.

        Burst-reads the six output bytes (0x28-0x2D) so X, Y and Z come
        from a single sample (``BDU`` = 1).

        Returns:
            tuple: (x, y, z) acceleration in *g*.
        """
        raw = self._read_burst(self._REG_OUT_X_L, 6)
        scale = self._sensitivity_mg() / 1000.0
        return (self._decode_axis(raw[0], raw[1]) * scale,
                self._decode_axis(raw[2], raw[3]) * scale,
                self._decode_axis(raw[4], raw[5]) * scale)

    def read_magnetic(self):
        """Read 3-axis magnetic flux density.

        Burst-reads 0x03-0x08 (six bytes); the wire order is X, Z, Y and
        each value is high byte first. Reorders to X, Y, Z.

        Returns:
            tuple: (x, y, z) flux density in µT.
        """
        raw = self._mag_read_burst(self._MAG_REG_OUT_X_H, 6)
        xy_gain, z_gain = self._mag_gains()
        x = self._decode_mag_axis(raw[0], raw[1], xy_gain)
        z = self._decode_mag_axis(raw[2], raw[3], z_gain)
        y = self._decode_mag_axis(raw[4], raw[5], xy_gain)
        return (x, y, z)

    def _sensitivity_mg(self):
        """Accelerometer sensitivity of the current mode and range.

        Returns:
            int: Sensitivity in mg/LSB.
        """
        return _SENSITIVITY_MG[self._mode][self._range_g]


class LSM303Source:
    """Interrupt source constants for the LSM303 Full driver.

    Values match the ``source`` argument of
    :meth:`LSM303Full.enable_interrupt` / :meth:`LSM303Full.disable_interrupt`
    and the bit layout returned by :meth:`LSM303Full.poll_interrupt`.
    ``SOURCE_BOOT`` is pin-only (no register flag); ``SOURCE_MAG_DATA_READY``
    is on the dedicated magnetometer ``DRDY`` pin and cannot be routed.
    """

    SOURCE_DATA_READY     = 0x0001
    SOURCE_WATERMARK      = 0x0002
    SOURCE_OVERRUN        = 0x0004
    SOURCE_IA1            = 0x0008
    SOURCE_IA2            = 0x0010
    SOURCE_CLICK          = 0x0020
    SOURCE_BOOT           = 0x0040
    SOURCE_MAG_DATA_READY = 0x0080


class LSM303Full(LSM303Minimal):
    """LSM303 full interface — extends :class:`LSM303Minimal`.

    Adds range / data-rate / mode selection, raw counts, status, the
    high-pass filter, the 32-level FIFO, both inertial interrupt generators,
    click detection, interrupt routing and delivery on INT1 / INT2, memory
    reboot, magnetometer gain / rate / mode, temperature, compass heading
    and the magnetometer identity.
    """

    # Source routing: 'int1', 'int2', 'both' and the routing bit in
    # CTRL_REG3 (INT1) / CTRL_REG6 (INT2). The magnetometer data-ready
    # source is pin-only.
    _ROUTE = {
        LSM303Source.SOURCE_DATA_READY:     ('int1', 0x10),
        LSM303Source.SOURCE_WATERMARK:      ('int1', 0x04),
        LSM303Source.SOURCE_OVERRUN:        ('int1', 0x02),
        LSM303Source.SOURCE_IA1:            ('both', 0x40),
        LSM303Source.SOURCE_IA2:            ('both', 0x20),
        LSM303Source.SOURCE_CLICK:          ('both', 0x80),
        LSM303Source.SOURCE_BOOT:           ('int2', 0x10),
    }

    def __init__(self, accel_connection, mag_connection):
        """Construct the driver and run the Initialization Sequence.

        Args:
            accel_connection: ``RegisterConnection`` bound to 0x19.
            mag_connection: ``RegisterConnection`` bound to 0x1E.

        Raises:
            ValueError: If the magnetometer identity is wrong.
        """
        super().__init__(accel_connection, mag_connection)
        self._enabled1 = 0
        self._enabled2 = 0
        self._int_callback = None
        self._int_handler = None
        self._poll_thread = None

    # ---- accelerometer configuration ------------------------------------

    def set_range(self, range_g):
        """Set the accelerometer full-scale range.

        Args:
            range_g: One of 2, 4, 8, 16 (*g*).

        Raises:
            ValueError: If ``range_g`` is not one of 2, 4, 8, 16.
        """
        if range_g not in _RANGES:
            raise ValueError('range_g must be one of 2, 4, 8, 16')
        fs = _RANGES.index(range_g) << 4
        ctrl4 = self._read_reg(self._REG_CTRL_REG4)
        self._write_reg(self._REG_CTRL_REG4, (ctrl4 & ~0x30) | fs)
        self._range_g = range_g

    def set_data_rate(self, rate_hz):
        """Set the accelerometer output data rate (or power down).

        Args:
            rate_hz: One of 0 (power-down), 1, 10, 25, 50, 100, 200, 400,
                1344, 1620, 5376. 1620 and 5376 are low-power only; 1344 is
                normal / high-resolution only.

        Raises:
            ValueError: If the rate is unknown or illegal in the current mode.
        """
        code = None
        for hz, c in _RATE_CODES:
            if hz == rate_hz:
                code = c
                break
        if code is None:
            raise ValueError('rate_hz must be one of 0, 1, 10, 25, 50, 100, '
                             '200, 400, 1344, 1620, 5376')
        if rate_hz in _LP_ONLY_RATES and self._mode != _MODE_LP:
            raise ValueError('rate {} Hz is low-power mode only'.format(rate_hz))
        if rate_hz in _NON_LP_ONLY_RATES and self._mode == _MODE_LP:
            raise ValueError('rate {} Hz is not available in low-power mode'.format(rate_hz))
        ctrl1 = self._read_reg(self._REG_CTRL_REG1)
        self._write_reg(self._REG_CTRL_REG1, (ctrl1 & 0x0F) | (code << 4))
        self._rate_hz = rate_hz
        _delay_ms(self._settle_ms())

    def set_mode(self, mode):
        """Set the accelerometer operating mode.

        Args:
            mode: 0 = low-power (8-bit), 1 = normal (10-bit),
                2 = high-resolution (12-bit).

        Raises:
            ValueError: If ``mode`` is invalid or the current rate is illegal
                in the requested mode.
        """
        if mode not in (_MODE_LP, _MODE_NORMAL, _MODE_HR):
            raise ValueError('mode must be 0 (low-power), 1 (normal) or 2 (high-resolution)')
        if mode == _MODE_LP and self._rate_hz in _NON_LP_ONLY_RATES:
            raise ValueError('rate {} Hz is not available in low-power mode'.format(self._rate_hz))
        if mode != _MODE_LP and self._rate_hz in _LP_ONLY_RATES:
            raise ValueError('rate {} Hz is low-power mode only'.format(self._rate_hz))
        ctrl1 = self._read_reg(self._REG_CTRL_REG1)
        ctrl4 = self._read_reg(self._REG_CTRL_REG4)
        if mode == _MODE_LP:
            ctrl1 |= 0x08
            ctrl4 &= ~0x08
        else:
            ctrl1 &= ~0x08
            if mode == _MODE_HR:
                ctrl4 |= 0x08
            else:
                ctrl4 &= ~0x08
        self._write_reg(self._REG_CTRL_REG1, ctrl1)
        self._write_reg(self._REG_CTRL_REG4, ctrl4)
        self._mode = mode
        _delay_ms(self._settle_ms())

    def set_axes(self, x=True, y=True, z=True):
        """Enable or disable the accelerometer axes.

        Args:
            x: Enable the X axis (default True).
            y: Enable the Y axis (default True).
            z: Enable the Z axis (default True).
        """
        ctrl1 = self._read_reg(self._REG_CTRL_REG1)
        axes = (1 if x else 0) | ((1 if y else 0) << 1) | ((1 if z else 0) << 2)
        self._write_reg(self._REG_CTRL_REG1, (ctrl1 & 0xF8) | axes)

    def power_down(self):
        """Stop accelerometer conversions (``ODR`` = 0000)."""
        ctrl1 = self._read_reg(self._REG_CTRL_REG1)
        self._write_reg(self._REG_CTRL_REG1, ctrl1 & 0x0F)
        self._rate_hz = 0

    # ---- accelerometer data ---------------------------------------------

    def read_acceleration_raw(self):
        """Read raw acceleration counts at the current resolution.

        Returns:
            tuple: (x, y, z) signed counts.
        """
        raw = self._read_burst(self._REG_OUT_X_L, 6)
        return (self._decode_axis(raw[0], raw[1]),
                self._decode_axis(raw[2], raw[3]),
                self._decode_axis(raw[4], raw[5]))

    def new_data_available(self):
        """Check whether a complete accelerometer sample is ready.

        Returns:
            bool: ``STATUS_REG_A.ZYXDA``.
        """
        return bool(self._read_reg(self._REG_STATUS) & 0x08)

    def read_status(self):
        """Read ``STATUS_REG_A``.

        Returns:
            int: Overrun and data-available flags.
        """
        return self._read_reg(self._REG_STATUS)

    # ---- high-pass filter ------------------------------------------------

    def set_high_pass(self, mode=0, cutoff=0, filter_output=False,
                      on_ia1=False, on_ia2=False, on_click=False):
        """Configure the accelerometer high-pass filter.

        Args:
            mode: ``HPM`` 0-3.
            cutoff: ``HPCF`` code 0-3 (no Hz values are tabulated).
            filter_output: Route filtered data to the output registers (``FDS``).
            on_ia1: Apply the filter to generator 1.
            on_ia2: Apply the filter to generator 2.
            on_click: Apply the filter to the click function.

        Raises:
            ValueError: If ``mode`` or ``cutoff`` is outside 0-3.
        """
        if mode not in (0, 1, 2, 3):
            raise ValueError('mode must be 0-3')
        if cutoff not in (0, 1, 2, 3):
            raise ValueError('cutoff must be 0-3')
        value = (mode << 6) | (cutoff << 4)
        if filter_output:
            value |= 0x08
        if on_click:
            value |= 0x04
        if on_ia2:
            value |= 0x02
        if on_ia1:
            value |= 0x01
        self._write_reg(self._REG_CTRL_REG2, value)

    def reset_high_pass(self):
        """Reset the high-pass filter by reading ``REFERENCE_A``.

        Returns:
            int: The reference value that was read.
        """
        return self._read_reg(self._REG_REFERENCE)

    def set_reference(self, value):
        """Write the high-pass reference value.

        Args:
            value: 0-255.

        Raises:
            ValueError: If ``value`` is outside 0-255.
        """
        if not isinstance(value, int) or value < 0 or value > 255:
            raise ValueError('value must be 0-255')
        self._write_reg(self._REG_REFERENCE, value)

    # ---- FIFO ------------------------------------------------------------

    def set_fifo(self, mode, watermark=0, trigger_int2=False):
        """Configure the 32-level accelerometer FIFO.

        Modes 1-3 set ``FIFO_EN``; returning to bypass (mode 0) clears the
        buffer.

        Args:
            mode: 0 bypass, 1 FIFO, 2 stream, 3 trigger.
            watermark: ``FTH`` 0-31.
            trigger_int2: Trigger source for trigger mode (False = INT1).

        Raises:
            ValueError: If ``mode`` is not 0-3 or ``watermark`` is 0-31.
        """
        if mode not in (0, 1, 2, 3):
            raise ValueError('mode must be 0 (bypass), 1 (FIFO), 2 (stream) or 3 (trigger)')
        if watermark < 0 or watermark > 31:
            raise ValueError('watermark must be 0-31')
        value = (mode << 6) | (0x20 if trigger_int2 else 0) | watermark
        self._write_reg(self._REG_FIFO_CTRL, value)
        ctrl5 = self._read_reg(self._REG_CTRL_REG5)
        if mode == 0:
            ctrl5 &= ~0x40
        else:
            ctrl5 |= 0x40
        self._write_reg(self._REG_CTRL_REG5, ctrl5)

    def fifo_level(self):
        """Read the number of unread sample sets in the FIFO.

        Returns:
            int: ``FSS<4:0>`` (0-32).
        """
        return self._read_reg(self._REG_FIFO_SRC) & 0x1F

    def read_fifo(self, count=None):
        """Drain sample sets from the FIFO.

        Args:
            count: Maximum number of sample sets (default: all ``FSS``).

        Returns:
            list: ``(x, y, z)`` tuples in *g*, oldest first.
        """
        level = self.fifo_level()
        n = level if count is None else min(count, level)
        samples = []
        scale = self._sensitivity_mg() / 1000.0
        for _ in range(n):
            raw = self._read_burst(self._REG_OUT_X_L, 6)
            samples.append((self._decode_axis(raw[0], raw[1]) * scale,
                            self._decode_axis(raw[2], raw[3]) * scale,
                            self._decode_axis(raw[4], raw[5]) * scale))
        return samples

    def read_fifo_status(self):
        """Decode ``FIFO_SRC_REG_A``.

        Returns:
            tuple: (watermark, overrun, empty, level).
        """
        raw = self._read_reg(self._REG_FIFO_SRC)
        return (bool(raw & 0x80), bool(raw & 0x40), bool(raw & 0x20), raw & 0x1F)

    # ---- inertial interrupt generators ----------------------------------

    def _generator_regs(self, generator):
        """Return the (cfg, threshold, duration) registers of a generator."""
        if generator == 1:
            return self._REG_INT1_CFG, self._REG_INT1_THS, self._REG_INT1_DUR
        if generator == 2:
            return self._REG_INT2_CFG, self._REG_INT2_THS, self._REG_INT2_DUR
        raise ValueError('generator must be 1 or 2')

    def _threshold_code(self, threshold_g):
        """Convert a threshold in *g* to the 7-bit register code."""
        lsb_g = float(self._range_g) / 128.0
        return max(0, min(127, _round(threshold_g / lsb_g)))

    def _duration_code(self, duration_ms, maximum=127):
        """Convert a duration in ms to the ODR-based register code."""
        if duration_ms <= 0 or self._rate_hz <= 0:
            return 0
        return max(0, min(maximum, _round(duration_ms * self._rate_hz / 1000.0)))

    def set_motion_interrupt(self, generator, events, threshold_g,
                             duration_ms=0, and_mode=False):
        """Configure an inertial interrupt generator.

        Args:
            generator: 1 or 2.
            events: 6-bit ``ZHIE..XLIE`` mask (0-0x3F).
            threshold_g: Threshold in *g*.
            duration_ms: Minimum duration (default 0).
            and_mode: True for AND (``AOI``), False for OR.

        Raises:
            ValueError: If ``generator`` or ``events`` is invalid.
        """
        cfg_reg, ths_reg, dur_reg = self._generator_regs(generator)
        if events < 0 or events > 0x3F:
            raise ValueError('events must be a 6-bit mask (0-0x3F)')
        cfg = (0x80 if and_mode else 0x00) | (events & 0x3F)
        self._write_reg(cfg_reg, cfg)
        self._write_reg(ths_reg, self._threshold_code(threshold_g))
        self._write_reg(dur_reg, self._duration_code(duration_ms))

    def set_free_fall(self, generator=1, threshold_g=0.35, duration_ms=30):
        """Configure the free-fall detector (all axes low, AND mode).

        Args:
            generator: 1 or 2 (default 1).
            threshold_g: Threshold in *g* (default 0.35).
            duration_ms: Minimum duration (default 30).
        """
        cfg_reg, ths_reg, dur_reg = self._generator_regs(generator)
        self._write_reg(cfg_reg, 0x95)
        self._write_reg(ths_reg, self._threshold_code(threshold_g))
        self._write_reg(dur_reg, self._duration_code(duration_ms))

    def set_orientation(self, generator=1, position=False, four_d=False):
        """Configure 6D / 4D orientation detection.

        Args:
            generator: 1 or 2 (default 1).
            position: True for position recognition, False for movement.
            four_d: Enable 4D detection (disables the Z zones).
        """
        cfg_reg, _, _ = self._generator_regs(generator)
        axis_zones = 0x0F if four_d else 0x3F
        self._write_reg(cfg_reg, (0xC0 if position else 0x40) | axis_zones)
        ctrl5 = self._read_reg(self._REG_CTRL_REG5)
        d4d_bit = 0x04 if generator == 1 else 0x01
        if four_d:
            ctrl5 |= d4d_bit
        else:
            ctrl5 &= ~d4d_bit
        self._write_reg(self._REG_CTRL_REG5, ctrl5)

    def set_latch(self, generator, enabled):
        """Latch a generator's interrupt until its source register is read.

        Args:
            generator: 1 or 2.
            enabled: True to latch.
        """
        if generator not in (1, 2):
            raise ValueError('generator must be 1 or 2')
        bit = 0x08 if generator == 1 else 0x02
        ctrl5 = self._read_reg(self._REG_CTRL_REG5)
        if enabled:
            ctrl5 |= bit
        else:
            ctrl5 &= ~bit
        self._write_reg(self._REG_CTRL_REG5, ctrl5)

    def read_interrupt_source(self, generator):
        """Read a generator's source register — **clears** the interrupt.

        Args:
            generator: 1 or 2.

        Returns:
            int: The source byte.
        """
        if generator not in (1, 2):
            raise ValueError('generator must be 1 or 2')
        return self._read_reg(self._REG_INT1_SRC if generator == 1 else self._REG_INT2_SRC)

    # ---- click detection -------------------------------------------------

    def set_click(self, single=0, double=0, threshold_g=0.5,
                  time_limit_ms=10, latency_ms=20, window_ms=50):
        """Configure single / double click detection.

        Args:
            single: Axis mask of single-click enables (X=1, Y=2, Z=4).
            double: Axis mask of double-click enables (X=1, Y=2, Z=4).
            threshold_g: Click threshold in *g*.
            time_limit_ms: Click time limit.
            latency_ms: Click latency.
            window_ms: Click window.

        Raises:
            ValueError: If an axis mask is outside 0-7.
        """
        if single < 0 or single > 7 or double < 0 or double > 7:
            raise ValueError('single / double must be axis masks (0-7: X=1, Y=2, Z=4)')
        cfg = ((single & 0x01) | ((single & 0x02) << 1) | ((single & 0x04) << 2) |
               ((double & 0x01) << 1) | ((double & 0x02) << 2) | ((double & 0x04) << 3))
        self._write_reg(self._REG_CLICK_CFG, cfg)
        self._write_reg(self._REG_CLICK_THS, self._threshold_code(threshold_g))
        self._write_reg(self._REG_TIME_LIMIT, self._duration_code(time_limit_ms, 127))
        self._write_reg(self._REG_TIME_LATENCY, self._duration_code(latency_ms, 255))
        self._write_reg(self._REG_TIME_WINDOW, self._duration_code(window_ms, 255))

    def read_click_source(self):
        """Read ``CLICK_SRC_A`` — **clears** the click interrupt.

        Returns:
            int: The click source byte.
        """
        return self._read_reg(self._REG_CLICK_SRC)

    def set_interrupt_polarity(self, active_low):
        """Set the shared INT1 / INT2 polarity (``H_LACTIVE``).

        Args:
            active_low: True for active-low, False for active-high.
        """
        ctrl6 = self._read_reg(self._REG_CTRL_REG6)
        if active_low:
            ctrl6 |= 0x02
        else:
            ctrl6 &= ~0x02
        self._write_reg(self._REG_CTRL_REG6, ctrl6)

    # ---- interrupt routing and delivery ----------------------------------

    def _route(self, source, pin):
        """Resolve a source to (register, mask, track) for routing."""
        if source not in self._ROUTE:
            raise ValueError('unknown interrupt source 0x{:04X}'.format(source))
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        where, bit = self._ROUTE[source]
        if where == 'int1':
            return self._REG_CTRL_REG3, bit, 1
        if where == 'int2':
            return self._REG_CTRL_REG6, bit, 2
        if pin == 1:
            return self._REG_CTRL_REG3, bit, 1
        return self._REG_CTRL_REG6, bit, 2

    def _source_bit(self, source):
        """Return the poll_interrupt bit index of a source."""
        return {LSM303Source.SOURCE_DATA_READY: 0,
                LSM303Source.SOURCE_WATERMARK: 1,
                LSM303Source.SOURCE_OVERRUN: 2,
                LSM303Source.SOURCE_IA1: 3,
                LSM303Source.SOURCE_IA2: 4,
                LSM303Source.SOURCE_CLICK: 5,
                LSM303Source.SOURCE_BOOT: 6,
                LSM303Source.SOURCE_MAG_DATA_READY: 7}[source]

    def enable_interrupt(self, source, pin=1):
        """Route one interrupt source to INT``pin``.

        Args:
            source: One of the :class:`LSM303Source` constants.
            pin: 1 or 2 (default 1).

        Raises:
            ValueError: If ``source`` is unknown, ``pin`` is invalid, or the
                source is the magnetometer DRDY (not routable).
        """
        if source == LSM303Source.SOURCE_MAG_DATA_READY:
            raise ValueError('magnetometer DRDY is on a dedicated pin and cannot be routed')
        reg, mask, track = self._route(source, pin)
        self._write_reg(reg, self._read_reg(reg) | mask)
        if track == 1:
            self._enabled1 |= 1 << self._source_bit(source)
        else:
            self._enabled2 |= 1 << self._source_bit(source)

    def disable_interrupt(self, source, pin=1):
        """Remove one interrupt source from INT``pin``.

        Args:
            source: One of the :class:`LSM303Source` constants.
            pin: 1 or 2 (default 1).
        """
        if source == LSM303Source.SOURCE_MAG_DATA_READY:
            raise ValueError('magnetometer DRDY is on a dedicated pin and cannot be routed')
        reg, mask, track = self._route(source, pin)
        self._write_reg(reg, self._read_reg(reg) & ~mask)
        if track == 1:
            self._enabled1 &= ~(1 << self._source_bit(source))
        else:
            self._enabled2 &= ~(1 << self._source_bit(source))

    def on_interrupt(self, callback, pin=1):
        """Subscribe to INT``pin`` assertions.

        Uses the connection's ``int_pin``; when no INT pin is wired the
        driver falls back to a 5 ms polling thread.

        Args:
            callback: Function called with the status int.
            pin: 1 or 2 (default 1).

        Raises:
            ValueError: If ``pin`` is not 1 or 2.
        """
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        self._int_callback = callback
        int_pin = getattr(self._accel, 'int_pin', None)
        if int_pin is not None and hasattr(int_pin, 'on_edge'):
            self._int_handler = lambda p: self._dispatch_int()
            int_pin.on_edge(self._int_handler, getattr(int_pin, 'FALLING', 2))
        else:
            self._start_polling_fallback()

    def off_interrupt(self, pin=1):
        """Unsubscribe from INT``pin`` assertions.

        Args:
            pin: 1 or 2 (default 1).
        """
        self._int_callback = None
        int_pin = getattr(self._accel, 'int_pin', None)
        handler = self._int_handler
        self._int_handler = None
        if int_pin is not None and handler is not None and hasattr(int_pin, 'off_edge'):
            int_pin.off_edge(handler)
        if self._poll_thread is not None:
            try:
                self._poll_thread.deinit()
            except Exception:
                pass
            self._poll_thread = None

    def _dispatch_int(self):
        """Poll the sources and deliver a non-zero status to the callback."""
        status = self.poll_interrupt()
        if status and self._int_callback is not None:
            self._int_callback(status)

    def _start_polling_fallback(self):
        """Start the 5 ms polling thread used when no INT pin is wired."""
        try:
            import _thread
        except ImportError:
            return

        def loop():
            while self._int_callback is not None:
                self._dispatch_int()
                import time
                if hasattr(time, 'sleep_ms'):
                    time.sleep_ms(5)
                else:
                    time.sleep(0.005)

        self._poll_thread = _thread.start_new_thread(loop, ())

    def poll_interrupt(self):
        """Read every enabled source and return the combined status.

        **Reading ``INTx_SOURCE_A`` / ``CLICK_SRC_A`` clears their latched
        flags.**

        Returns:
            int: Combined status — bit 0 data-ready, 1 watermark, 2 overrun,
            3 ``IA1``, 4 ``IA2``, 5 click, 7 magnetometer data-ready.
        """
        src = LSM303Source
        enabled = self._enabled1 | self._enabled2
        status = 0
        if (enabled >> 0) & 1 and self._read_reg(self._REG_STATUS) & 0x08:
            status |= src.SOURCE_DATA_READY
        if (enabled >> 1) & 1 or (enabled >> 2) & 1:
            fifo = self._read_reg(self._REG_FIFO_SRC)
            if (enabled >> 1) & 1 and fifo & 0x80:
                status |= src.SOURCE_WATERMARK
            if (enabled >> 2) & 1 and fifo & 0x40:
                status |= src.SOURCE_OVERRUN
        if (enabled >> 3) & 1 and self._read_reg(self._REG_INT1_SRC) & 0x40:
            status |= src.SOURCE_IA1
        if (enabled >> 4) & 1 and self._read_reg(self._REG_INT2_SRC) & 0x40:
            status |= src.SOURCE_IA2
        if (enabled >> 5) & 1 and self._read_reg(self._REG_CLICK_SRC) & 0x40:
            status |= src.SOURCE_CLICK
        if self._mag_read_reg(self._MAG_REG_SR) & 0x01:
            status |= src.SOURCE_MAG_DATA_READY
        return status

    # ---- magnetometer configuration -------------------------------------

    def set_mag_range(self, range_ut):
        """Set the magnetometer gain / full-scale range.

        Args:
            range_ut: One of 130, 190, 250, 400, 470, 560, 810 (µT).

        Raises:
            ValueError: If ``range_ut`` is not a supported range.
        """
        if range_ut not in _MAG_RANGE_CODE:
            raise ValueError('range_ut must be one of 130, 190, 250, 400, 470, 560, 810')
        self._mag_gain_code = _MAG_RANGE_CODE[range_ut]
        self._mag_write_reg(self._MAG_REG_CRB, self._mag_gain_code)

    def set_mag_data_rate(self, rate_hz):
        """Set the magnetometer output data rate.

        Args:
            rate_hz: One of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220.

        Raises:
            ValueError: If ``rate_hz`` is not a supported rate.
        """
        code = None
        for hz, c in _MAG_RATE_CODES:
            if hz == rate_hz:
                code = c
                break
        if code is None:
            raise ValueError('rate_hz must be one of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220')
        cra = self._mag_read_reg(self._MAG_REG_CRA)
        self._mag_write_reg(self._MAG_REG_CRA, (cra & 0xE3) | code)
        self._mag_rate_hz = rate_hz

    def set_mag_mode(self, mode):
        """Set the magnetometer operating mode.

        Args:
            mode: 0 continuous, 1 single conversion, 2 sleep.

        Raises:
            ValueError: If ``mode`` is not 0, 1 or 2.
        """
        if mode not in (0, 1, 2):
            raise ValueError('mode must be 0 (continuous), 1 (single) or 2 (sleep)')
        code = {0: 0x00, 1: 0x01, 2: 0x03}[mode]
        self._mag_write_reg(self._MAG_REG_MR, code)
        if mode == 1:
            _delay_ms(_ceil_ms(1000.0 / self._mag_rate_hz))

    def read_magnetic_raw(self):
        """Read raw magnetometer counts (reordered X, Y, Z).

        Returns:
            tuple: (x, y, z) signed counts.
        """
        raw = self._mag_read_burst(self._MAG_REG_OUT_X_H, 6)
        x = to_signed((raw[0] << 8) | raw[1], 16)
        z = to_signed((raw[2] << 8) | raw[3], 16)
        y = to_signed((raw[4] << 8) | raw[5], 16)
        return (x, y, z)

    def mag_data_ready(self):
        """Check whether a new magnetometer sample is available.

        Returns:
            bool: ``SR_REG_M.DRDY``.
        """
        return bool(self._mag_read_reg(self._MAG_REG_SR) & 0x01)

    def read_mag_status(self):
        """Decode ``SR_REG_M``.

        Returns:
            tuple: (drdy, lock).
        """
        raw = self._mag_read_reg(self._MAG_REG_SR)
        return (bool(raw & 0x01), bool(raw & 0x02))

    def read_mag_identity(self):
        """Read the magnetometer identity bytes.

        Returns:
            bytes: ``IRA/IRB/IRC`` (0x48 0x34 0x33).
        """
        return bytes(self._mag_read_burst(self._MAG_REG_IRA, 3))

    def enable_temperature(self, enabled):
        """Enable or disable the magnetometer temperature sensor.

        Args:
            enabled: True to enable ``TEMP_EN``.
        """
        cra = self._mag_read_reg(self._MAG_REG_CRA)
        if enabled:
            cra |= 0x80
        else:
            cra &= ~0x80
        self._mag_write_reg(self._MAG_REG_CRA, cra)

    def read_temperature_delta(self):
        """Read the relative magnetometer temperature.

        Enables ``TEMP_EN`` on first use.

        Returns:
            float: Relative temperature in °C (8 LSB/°C, no absolute
            reference).
        """
        if not self._mag_read_reg(self._MAG_REG_CRA) & 0x80:
            self.enable_temperature(True)
            _delay_ms(_ceil_ms(1000.0 / self._mag_rate_hz))
        raw = to_signed((self._mag_read_reg(self._MAG_REG_TEMP_H) << 8) |
                        self._mag_read_reg(self._MAG_REG_TEMP_L), 16)
        return (raw >> 4) / 8.0

    def heading(self, tilt_compensate=True):
        """Compute the compass heading in degrees.

        Args:
            tilt_compensate: True to use the accelerometer for pitch / roll
                compensation, False for a flat compass.

        Returns:
            float: Heading in degrees (0-360, ``atan2(y, x)`` convention).
        """
        mx, my, mz = self.read_magnetic()
        if tilt_compensate:
            ax, ay, az = self.read_acceleration()
            roll = math.atan2(ay, az)
            pitch = math.atan2(-ax, ay * math.sin(roll) + az * math.cos(roll))
            xh = mx * math.cos(pitch) + mz * math.sin(pitch)
            yh = (mx * math.sin(roll) * math.sin(pitch) + my * math.cos(roll) -
                  mz * math.sin(roll) * math.cos(pitch))
            angle = math.atan2(yh, xh)
        else:
            angle = math.atan2(my, mx)
        degrees = angle * 180.0 / math.pi
        return degrees + 360.0 if degrees < 0 else degrees

    # ---- system ----------------------------------------------------------

    def reboot(self):
        """Reboot the accelerometer memory content and re-run ``init``.

        The datasheet does not say whether the control registers survive, so
        the Initialization Sequence is re-run afterwards.
        """
        ctrl5 = self._read_reg(self._REG_CTRL_REG5)
        self._write_reg(self._REG_CTRL_REG5, ctrl5 | 0x80)
        _delay_ms(5)
        self._range_g = _DEFAULT_RANGE_G
        self._mode = _DEFAULT_MODE
        self._rate_hz = _DEFAULT_RATE_HZ
        self._enabled1 = 0
        self._enabled2 = 0
        self._run_init_sequence()
