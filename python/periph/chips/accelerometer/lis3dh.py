"""LIS3DH — 3-axis ultra-low-power MEMS accelerometer (STMicroelectronics).

Triaxial accelerometer with 16-bit left-justified output and four
selectable full-scale ranges (±2, ±4, ±8, ±16 *g*). Three operating modes
trade resolution for current: low-power (8-bit), normal (10-bit) and
high-resolution (12-bit), across output data rates from 1 Hz to 5.376 kHz.
On top of the conversions the chip has a 32-level FIFO, a selectable
high-pass filter, two independent inertial interrupt generators
(wake-up, free-fall, 6D/4D orientation), single/double-click detection, a
sleep-to-wake activity function, a self-test actuator and an auxiliary
three-channel 10-bit ADC whose third channel can be switched to the
on-chip temperature sensor.

Communicates over I²C at address 0x18 (SA0 = GND) or 0x19 (SA0 = VDD_IO).
The chip also supports 3-/4-wire SPI, but that is out of scope for this
driver. After the slave address the master sends an 8-bit sub-address:
the 7 LSbs are the register address and the MSb enables address
auto-increment, so every multi-byte transfer ORs 0x80 into the register
address (single-register accesses do not).

The driver is platform-agnostic — it only calls the connection interface.
The same file is used on MicroPython, CircuitPython and Linux hosts.
"""

import math

from periph.connection.register import to_signed

# Full-scale ranges in g.
_RANGES = (2, 4, 8, 16)

# Operating modes: 0 = low-power (8-bit), 1 = normal (10-bit),
# 2 = high-resolution (12-bit).
_MODE_LP = 0
_MODE_NORMAL = 1
_MODE_HR = 2

# Output data rates in Hz and their CTRL_REG1 ODR<3:0> codes (bits 7:4).
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

# Rates that only exist in low-power mode, and rates that only exist
# outside it.
_LP_ONLY_RATES = (1620, 5376)
_NON_LP_ONLY_RATES = (1344,)

# Sensitivity in mg/LSB by mode and full-scale range (datasheet Table 4).
# Note the ±16 g high-resolution value is 12 mg/LSB, not 8.
_SENSITIVITY_MG = {
    _MODE_LP:     {2: 16, 4: 32, 8: 64, 16: 192},
    _MODE_NORMAL: {2: 4, 4: 8, 8: 16, 16: 48},
    _MODE_HR:     {2: 1, 4: 2, 8: 4, 16: 12},
}

# Right-shift applied to the left-justified 16-bit output pair, by mode.
_SHIFT = {_MODE_LP: 8, _MODE_NORMAL: 6, _MODE_HR: 4}

# Interrupt-threshold LSB in mg by full-scale range (full-scale / 128;
# the ±16 g value is taken as printed: 186 mg).
_THRESHOLD_LSB_MG = {2: 16, 4: 32, 8: 62, 16: 186}

# Defaults baked into the Minimal driver: ±2 g, 100 Hz, high-resolution.
_DEFAULT_RANGE_G = 2
_DEFAULT_RATE_HZ = 100
_DEFAULT_MODE = _MODE_HR


def _delay_ms(ms):
    """Sleep for `ms` milliseconds on every supported Python target.

    Args:
        ms: Number of milliseconds to sleep; 0 or less returns immediately.
    """
    if ms <= 0:
        return
    import time
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


def _round(value):
    """Round half away from zero.

    Matches C's `round()`, JavaScript's `Math.round` and Go's
    `math.Round` for the sign handling, so every language computes the
    same register codes.

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


class LIS3DHMinimal:
    """LIS3DH 3-axis accelerometer — minimal interface.

    Reads X, Y, Z acceleration in *g* with sensible defaults; no
    configuration is required beyond the connection.

    The constructor runs the datasheet Initialization Sequence: it
    verifies ``WHO_AM_I`` (0x0F) == 0x33, puts the control registers in a
    known state (the chip has no soft reset), then configures ±2 *g*,
    100 Hz ODR, high-resolution 12-bit mode with ``BDU`` on and waits
    7/ODR (70 ms) for the converter to turn on.

    Default configuration (baked in at construction):
        - Range ±2 *g* (1 mg/LSB)
        - 100 Hz output data rate
        - High-resolution (12-bit) mode, all three axes enabled
        - ``BDU`` = 1 (block data update)
        - High-pass filter bypassed, FIFO bypassed
        - All interrupt routing cleared
        - Aux ADC and temperature sensor off
        - ``CTRL_REG0`` untouched

    Args:
        connection: Configured ``RegisterConnection`` (I²C; address 0x18
            with SA0 = GND, 0x19 with SA0 = VDD_IO).

    Raises:
        ValueError: If ``WHO_AM_I`` is not 0x33 (wrong chip, wrong
            address, or a wiring problem).
    """

    # Register map.
    _REG_STATUS_AUX   = 0x07
    _REG_OUT_ADC1_L   = 0x08
    _REG_OUT_ADC1_H   = 0x09
    _REG_OUT_ADC2_L   = 0x0A
    _REG_OUT_ADC2_H   = 0x0B
    _REG_OUT_ADC3_L   = 0x0C
    _REG_OUT_ADC3_H   = 0x0D
    _REG_WHO_AM_I     = 0x0F
    _REG_CTRL_REG0    = 0x1E
    _REG_TEMP_CFG     = 0x1F
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
    _REG_ACT_THS      = 0x3E
    _REG_ACT_DUR      = 0x3F

    # WHO_AM_I (0x0F) reset value.
    _WHO_AM_I_VALUE = 0x33

    # Control-register defaults of the Initialization Sequence.
    _CTRL_REG4_DEFAULT = 0x88   # BDU=1, FS=00 (±2 g), HR=1
    _CTRL_REG1_DEFAULT = 0x57   # ODR=0101 (100 Hz), LPen=0, X/Y/Z enabled

    def __init__(self, connection):
        """Construct the driver and run the Initialization Sequence.

        Args:
            connection: Configured ``RegisterConnection`` bound to the
                chip's address.

        Raises:
            ValueError: If ``WHO_AM_I`` is not 0x33.
        """
        self._connection = connection
        self._range_g = _DEFAULT_RANGE_G
        self._mode = _DEFAULT_MODE
        self._rate_hz = _DEFAULT_RATE_HZ
        self._run_init_sequence()

    def _run_init_sequence(self):
        """Run the datasheet Initialization Sequence (steps 2-6)."""
        # Step 2: verify WHO_AM_I.
        chip_id = self._read_reg(self._REG_WHO_AM_I)
        if chip_id != self._WHO_AM_I_VALUE:
            raise ValueError('LIS3DH WHO_AM_I: expected 0x{:02X}, got 0x{:02X}'.format(
                self._WHO_AM_I_VALUE, chip_id))

        # Step 3: no soft reset — put the control registers in a known
        # state. CTRL_REG0 and the interrupt-generator registers are left
        # untouched (reserved registers are never written).
        self._write_reg(self._REG_CTRL_REG2, 0x00)
        self._write_reg(self._REG_CTRL_REG3, 0x00)
        self._write_reg(self._REG_CTRL_REG5, 0x00)
        self._write_reg(self._REG_CTRL_REG6, 0x00)
        self._write_reg(self._REG_FIFO_CTRL, 0x00)
        self._write_reg(self._REG_TEMP_CFG, 0x00)

        # Step 4: block data update, ±2 g, high-resolution 12-bit mode.
        self._write_reg(self._REG_CTRL_REG4, self._CTRL_REG4_DEFAULT)

        # Step 5: leave power-down — this starts the conversions.
        self._write_reg(self._REG_CTRL_REG1, self._CTRL_REG1_DEFAULT)

        # Step 6: wait for the high-resolution turn-on (7/ODR = 70 ms at
        # 100 Hz) — the longer of the turn-on time and one ODR period.
        _delay_ms(self._settle_ms())

    def _write_reg(self, reg, value):
        """Write one byte to a register.

        Args:
            reg: Register address.
            value: Value to write.
        """
        self._connection.write_reg(reg, value & 0xFF)

    def _read_reg(self, reg):
        """Read one byte from a register.

        Args:
            reg: Register address.

        Returns:
            int: The register value.
        """
        return self._connection.read_reg(reg, 1)[0]

    def _read_burst(self, reg, length):
        """Read `length` consecutive bytes starting at `reg`.

        Args:
            reg: First register address.
            length: Number of bytes to read.

        Returns:
            bytes: The register values.
        """
        # Multi-byte I²C transfers set the sub-address MSb (auto-increment).
        sub = reg | 0x80 if length > 1 else reg
        return self._connection.read_reg(sub, length)

    def _settle_ms(self):
        """Milliseconds to wait after an ODR / mode change.

        Returns:
            int: The longer of the mode's turn-on time (datasheet
            Table 10) and one ODR period.
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
        """Decode one left-justified 16-bit output pair.

        Args:
            low: Low byte of the pair.
            high: High byte of the pair.

        Returns:
            int: The signed count at the current resolution.
        """
        raw16 = to_signed(low | (high << 8), 16)
        return raw16 >> _SHIFT[self._mode]

    def _sensitivity_mg(self):
        """Sensitivity of the current mode and range.

        Returns:
            int: Sensitivity in mg/LSB.
        """
        return _SENSITIVITY_MG[self._mode][self._range_g]

    def read(self):
        """Read 3-axis linear acceleration.

        Burst-reads the six output bytes (0x28-0x2D) with auto-increment
        so X, Y and Z come from a single sample (``BDU`` = 1),
        sign-extends the left-justified 16-bit pairs and scales them with
        the sensitivity of the current mode and range.

        Returns:
            tuple: (x, y, z) acceleration in *g*.
        """
        raw = self._read_burst(self._REG_OUT_X_L, 6)
        scale = self._sensitivity_mg() / 1000.0
        return (self._decode_axis(raw[0], raw[1]) * scale,
                self._decode_axis(raw[2], raw[3]) * scale,
                self._decode_axis(raw[4], raw[5]) * scale)


class LIS3DHSource:
    """Interrupt source constants for the LIS3DH Full driver.

    The values match the ``source`` argument of
    :meth:`LIS3DHFull.enable_interrupt` / :meth:`LIS3DHFull.disable_interrupt`
    and the bit layout returned by :meth:`LIS3DHFull.poll_interrupt` for
    bits 0-6. ``SOURCE_ACTIVITY`` and ``SOURCE_BOOT`` are pin-only: the
    chip has no register flag for them, so ``poll_interrupt`` never
    reports them.
    """

    SOURCE_DATA_READY     = 0x0001  # STATUS_REG.ZYXDA — INT1 only
    SOURCE_AUX_DATA_READY = 0x0002  # STATUS_REG_AUX.321DA — INT1 only
    SOURCE_WATERMARK      = 0x0004  # FIFO_SRC_REG.WTM — INT1 only
    SOURCE_OVERRUN        = 0x0008  # FIFO_SRC_REG.OVRN_FIFO — INT1 only
    SOURCE_IA1            = 0x0010  # inertial generator 1 — INT1 or INT2
    SOURCE_IA2            = 0x0020  # inertial generator 2 — INT1 or INT2
    SOURCE_CLICK          = 0x0040  # single / double click — INT1 or INT2
    SOURCE_ACTIVITY       = 0x0080  # sleep-to-wake activity — INT2 only
    SOURCE_BOOT           = 0x0100  # boot procedure running — INT2 only


class LIS3DHFull(LIS3DHMinimal):
    """LIS3DH full interface — extends :class:`LIS3DHMinimal` with the
    complete chip functionality.

    Adds range / data-rate / mode selection, raw counts, status and
    temperature reads, the auxiliary ADC, the high-pass filter, the
    32-level FIFO, both inertial interrupt generators (free-fall, 6D / 4D
    orientation), single / double click detection, the sleep-to-wake
    activity function, interrupt routing and delivery on INT1 / INT2,
    memory reboot, SDO pull-up control and the electrostatic self-test.
    """

    # Right-shift for the auxiliary ADC output (10-bit in normal and
    # high-resolution mode, 8-bit in low-power mode).
    _ADC_SHIFT = {_MODE_LP: 8, _MODE_NORMAL: 6, _MODE_HR: 6}

    # Source routing: 'int1' (INT1 only), 'int2' (INT2 only), 'both'.
    # The value is the bit in CTRL_REG3 (INT1) / CTRL_REG6 (INT2).
    _ROUTE = {
        LIS3DHSource.SOURCE_DATA_READY:     ('int1', 0x10),
        LIS3DHSource.SOURCE_AUX_DATA_READY: ('int1', 0x08),
        LIS3DHSource.SOURCE_WATERMARK:      ('int1', 0x04),
        LIS3DHSource.SOURCE_OVERRUN:        ('int1', 0x02),
        LIS3DHSource.SOURCE_IA1:            ('both', 0x40),
        LIS3DHSource.SOURCE_IA2:            ('both', 0x20),
        LIS3DHSource.SOURCE_CLICK:          ('both', 0x80),
        LIS3DHSource.SOURCE_ACTIVITY:       ('int2', 0x08),
        LIS3DHSource.SOURCE_BOOT:           ('int2', 0x10),
    }

    def __init__(self, connection):
        """Construct the driver and run the Initialization Sequence.

        Args:
            connection: Configured ``RegisterConnection`` bound to the
                chip's address.

        Raises:
            ValueError: If ``WHO_AM_I`` is not 0x33.
        """
        super().__init__(connection)
        self._enabled1 = 0
        self._enabled2 = 0
        self._int_callback = None
        self._int_handler = None
        self._poll_thread = None

    # ---- configuration ---------------------------------------------------

    def set_range(self, range_g):
        """Set the full-scale measurement range.

        Read-modify-writes the ``FS`` field of ``CTRL_REG4`` and updates
        the cached sensitivity used by :meth:`read` and :meth:`read_raw`.

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
        """Set the output data rate (or power down).

        Read-modify-writes the ``ODR`` field of ``CTRL_REG1`` and waits
        for the turn-on time before returning.

        Args:
            rate_hz: One of 0 (power-down), 1, 10, 25, 50, 100, 200,
                400, 1344, 1620, 5376. 1620 and 5376 exist in low-power
                mode only; 1344 in normal / high-resolution mode only.

        Raises:
            ValueError: If the rate is unknown or not allowed in the
                current operating mode.
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
        """Set the operating mode (resolution / power trade-off).

        Read-modify-writes ``LPen`` in ``CTRL_REG1`` and ``HR`` in
        ``CTRL_REG4``, updates the cached resolution and waits for the
        mode's turn-on time.

        Args:
            mode: 0 = low-power (8-bit), 1 = normal (10-bit),
                2 = high-resolution (12-bit).

        Raises:
            ValueError: If ``mode`` is not 0, 1 or 2, or if the current
                data rate is illegal in that mode (``LPen`` = 1 with
                ``HR`` = 1 is never written).
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
            ctrl1 |= 0x08                      # LPen = 1
            ctrl4 &= ~0x08                     # HR = 0 (LPen=1 with HR=1 not allowed)
        else:
            ctrl1 &= ~0x08                     # LPen = 0
            if mode == _MODE_HR:
                ctrl4 |= 0x08                  # HR = 1
            else:
                ctrl4 &= ~0x08                 # HR = 0
        self._write_reg(self._REG_CTRL_REG1, ctrl1)
        self._write_reg(self._REG_CTRL_REG4, ctrl4)
        self._mode = mode
        _delay_ms(self._settle_ms())

    def set_axes(self, x=True, y=True, z=True):
        """Enable or disable the individual measurement axes.

        Args:
            x: Enable the X axis (default True).
            y: Enable the Y axis (default True).
            z: Enable the Z axis (default True).
        """
        ctrl1 = self._read_reg(self._REG_CTRL_REG1)
        axes = (1 if x else 0) | ((1 if y else 0) << 1) | ((1 if z else 0) << 2)
        self._write_reg(self._REG_CTRL_REG1, (ctrl1 & 0xF8) | axes)

    def power_down(self):
        """Stop conversions by setting ``ODR`` = 0000.

        The register contents are retained.
        """
        ctrl1 = self._read_reg(self._REG_CTRL_REG1)
        self._write_reg(self._REG_CTRL_REG1, ctrl1 & 0x0F)
        self._rate_hz = 0

    # ---- data reads ------------------------------------------------------

    def read_raw(self):
        """Read the raw acceleration counts at the current resolution.

        Returns:
            tuple: (x, y, z) signed counts — 12-bit in high-resolution
            mode, 10-bit in normal mode, 8-bit in low-power mode.
        """
        raw = self._read_burst(self._REG_OUT_X_L, 6)
        return (self._decode_axis(raw[0], raw[1]),
                self._decode_axis(raw[2], raw[3]),
                self._decode_axis(raw[4], raw[5]))

    def new_data_available(self):
        """Check whether a complete X/Y/Z sample set is ready.

        Returns:
            bool: ``STATUS_REG.ZYXDA`` — True when all three axes have
            new data.
        """
        return bool(self._read_reg(self._REG_STATUS) & 0x08)

    def read_status(self):
        """Read the STATUS register.

        Returns:
            int: ``STATUS_REG`` byte — overrun (``ZYXOR``..``XOR``) and
            data-available (``ZYXDA``..``XDA``) flags.
        """
        return self._read_reg(self._REG_STATUS)

    def read_temperature_delta(self):
        """Read the relative on-chip temperature.

        Enables ``ADC_EN`` and ``TEMP_EN`` in ``TEMP_CFG_REG`` on first
        use (the ADC channel-3 conversions run through the same ADC) and
        waits one ODR period for the first conversion.

        The datasheet specifies only 1 digit/°C with no zero-degree
        reference, so the value is a relative delta, never an absolute
        temperature.

        Returns:
            int: Relative temperature in °C (8-bit two's complement,
            1 digit/°C).
        """
        cfg = self._read_reg(self._REG_TEMP_CFG)
        if (cfg & 0xC0) != 0xC0:
            self._write_reg(self._REG_TEMP_CFG, cfg | 0xC0)
            if self._rate_hz > 0:
                _delay_ms(_ceil_ms(1000.0 / self._rate_hz))
        return to_signed(self._read_reg(self._REG_OUT_ADC3_H), 8)

    def read_adc(self, channel):
        """Read one auxiliary ADC channel.

        Args:
            channel: 1, 2 or 3. Channel 3 is the temperature-sensor
                input while ``TEMP_EN`` = 1.

        Returns:
            float: Input voltage in V (1.200 V centre, ±400 mV range;
            the mapping is derived — see the spec's Implementation Notes).

        Raises:
            ValueError: If ``channel`` is not 1, 2 or 3, or if channel 3
                is requested while ``TEMP_EN`` = 1 (use
                :meth:`read_temperature_delta` instead).
        """
        if channel not in (1, 2, 3):
            raise ValueError('channel must be 1, 2 or 3')
        if channel == 3:
            cfg = self._read_reg(self._REG_TEMP_CFG)
            if cfg & 0x40:
                raise ValueError('channel 3 is the temperature sensor while TEMP_EN = 1')
        base = self._REG_OUT_ADC1_L + (channel - 1) * 2
        raw = self._read_burst(base, 2)
        raw16 = to_signed(raw[0] | (raw[1] << 8), 16)
        counts = raw16 >> self._ADC_SHIFT[self._mode]
        return 1.200 + counts * (0.400 / 512.0)

    def enable_adc(self, enabled):
        """Enable or disable the auxiliary ADC.

        Sets ``ADC_EN`` in ``TEMP_CFG_REG``.

        Args:
            enabled: True to enable the ADC, False to disable it.
        """
        cfg = self._read_reg(self._REG_TEMP_CFG)
        if enabled:
            cfg |= 0x80
        else:
            cfg &= ~0x80
        self._write_reg(self._REG_TEMP_CFG, cfg)

    # ---- high-pass filter ------------------------------------------------

    def set_high_pass(self, mode=0, cutoff=0, filter_output=False,
                      on_ia1=False, on_ia2=False, on_click=False):
        """Configure the high-pass filter (``CTRL_REG2``).

        Args:
            mode: ``HPM`` — 0 normal (reset by reading ``REFERENCE``),
                1 reference signal for filtering, 2 normal, 3 auto-reset
                on interrupt event.
            cutoff: ``HPCF<2:1>`` code 0-3 (the datasheet tabulates no
                Hz values; they scale with ODR).
            filter_output: Route the filtered data to the output
                registers and FIFO (``FDS``).
            on_ia1: Apply the filter to interrupt generator 1.
            on_ia2: Apply the filter to interrupt generator 2.
            on_click: Apply the filter to the click function.

        Raises:
            ValueError: If ``mode`` is not 0-3 or ``cutoff`` not 0-3.
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
        """Reset the filter by reading ``REFERENCE`` (``HPM`` = 00).

        Returns:
            int: The ``REFERENCE`` register value that was read.
        """
        return self._read_reg(self._REG_REFERENCE)

    def set_reference(self, value):
        """Write the high-pass reference value (``HPM`` = 01).

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
        """Configure the 32-level FIFO (``FIFO_CTRL_REG``).

        Sets ``FIFO_EN`` in ``CTRL_REG5`` for modes 1-3; returning to
        bypass (mode 0) clears the buffer. Only bypass mode empties the
        buffer — restart a FIFO mode through bypass after a full drain.

        Args:
            mode: 0 = bypass, 1 = FIFO, 2 = stream, 3 = stream-to-FIFO.
            watermark: Watermark level ``FTH`` 0-31 (default 0).
            trigger_int2: Trigger source for stream-to-FIFO: False =
                INT1, True = INT2 (``TR``).

        Raises:
            ValueError: If ``mode`` is not 0-3 or ``watermark`` outside
                0-31.
        """
        if mode not in (0, 1, 2, 3):
            raise ValueError('mode must be 0 (bypass), 1 (FIFO), 2 (stream) or 3 (stream-to-FIFO)')
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
            int: ``FSS<4:0>`` — 0-32 unread sample sets.
        """
        return self._read_reg(self._REG_FIFO_SRC) & 0x1F

    def read_fifo(self, count=None):
        """Drain sample sets from the FIFO.

        Each sample set is one 6-byte burst of ``0x28``-``0x2D``; the
        address auto-increment wraps back to ``0x28`` so a full drain is
        32 reads.

        Args:
            count: Maximum number of sample sets to read (default: all
                ``FSS`` entries currently in the FIFO). Never reads more
                than the FIFO actually holds.

        Returns:
            list: ``(x, y, z)`` tuples in *g*, oldest sample first.
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
        """Decode ``FIFO_SRC_REG``.

        Returns:
            tuple: ``(watermark, overrun, empty, level)`` — the three
            flags are booleans, ``level`` is ``FSS<4:0>`` (0-32).
        """
        raw = self._read_reg(self._REG_FIFO_SRC)
        return (bool(raw & 0x80), bool(raw & 0x40), bool(raw & 0x20), raw & 0x1F)

    # ---- inertial interrupt generators -----------------------------------

    def set_motion_interrupt(self, generator, events, threshold_g,
                             duration_ms=0, and_mode=False):
        """Configure an inertial interrupt generator (motion / threshold).

        Args:
            generator: 1 or 2 — the ``INT1`` or ``INT2`` register block.
            events: 6-bit ``ZHIE..XLIE`` event-enable mask (0-0x3F).
            threshold_g: Threshold in *g*; scaled with the current
                full-scale range (16 / 32 / 62 / 186 mg per LSb).
            duration_ms: Minimum duration the events must hold, in ms
                (``INTx_DURATION``, 1 LSb = 1/ODR; default 0).
            and_mode: True for an AND combination of the enabled events
                (``AOI`` = 1), False for OR.

        Raises:
            ValueError: If ``generator`` is not 1 or 2 or ``events`` is
                outside 0-0x3F.
        """
        cfg_reg, ths_reg, dur_reg = self._generator_regs(generator)
        if events < 0 or events > 0x3F:
            raise ValueError('events must be a 6-bit mask (0-0x3F)')
        cfg = (0x80 if and_mode else 0x00) | (events & 0x3F)
        self._write_reg(cfg_reg, cfg)
        self._write_reg(ths_reg, self._threshold_code(threshold_g))
        self._write_reg(dur_reg, self._duration_code(duration_ms))

    def set_free_fall(self, generator=1, threshold_g=0.35, duration_ms=30):
        """Configure the free-fall detector: all three axes low, AND mode.

        Convenience wrapper around the generator's ``INTx_CFG`` = 0x95
        (``AOI`` + ``XLIE``/``YLIE``/``ZLIE``).

        Args:
            generator: 1 or 2 (default 1).
            threshold_g: Free-fall threshold in *g* (default 0.35).
            duration_ms: Minimum time below the threshold in ms
                (default 30).

        Raises:
            ValueError: If ``generator`` is not 1 or 2.
        """
        cfg_reg, ths_reg, dur_reg = self._generator_regs(generator)
        self._write_reg(cfg_reg, 0x95)
        self._write_reg(ths_reg, self._threshold_code(threshold_g))
        self._write_reg(dur_reg, self._duration_code(duration_ms))

    def set_orientation(self, generator=1, position=False, four_d=False):
        """Configure 6D / 4D orientation detection.

        ``AOI`` / ``6D`` = 01 is *movement* recognition (a one-ODR pulse),
        11 is *position* recognition (level, active while the orientation
        is inside a known zone).

        Args:
            generator: 1 or 2 (default 1).
            position: False = 6D movement recognition, True = 6D
                position recognition.
            four_d: Enable 4D detection (``D4D_INTx``) — disables the
                Z-axis zones.

        Raises:
            ValueError: If ``generator`` is not 1 or 2.
        """
        cfg_reg, _, _ = self._generator_regs(generator)
        axis_zones = 0x0F if four_d else 0x3F
        cfg = (0xC0 if position else 0x40) | axis_zones
        self._write_reg(cfg_reg, cfg)
        ctrl5 = self._read_reg(self._REG_CTRL_REG5)
        d4d_bit = 0x04 if generator == 1 else 0x01
        if four_d:
            ctrl5 |= d4d_bit
        else:
            ctrl5 &= ~d4d_bit
        self._write_reg(self._REG_CTRL_REG5, ctrl5)

    def set_latch(self, generator, enabled):
        """Latch a generator's interrupt until its ``INTx_SRC`` is read.

        Args:
            generator: 1 or 2.
            enabled: True to latch (``LIR_INTx``), False for a
                self-resetting interrupt.

        Raises:
            ValueError: If ``generator`` is not 1 or 2.
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
        """Read a generator's ``INTx_SRC`` — **clears** the interrupt.

        Args:
            generator: 1 or 2.

        Returns:
            int: ``INTx_SRC`` byte (``IA`` = bit 6, per-axis event flags
            in bits 5-0).

        Raises:
            ValueError: If ``generator`` is not 1 or 2.
        """
        if generator not in (1, 2):
            raise ValueError('generator must be 1 or 2')
        return self._read_reg(self._REG_INT1_SRC if generator == 1 else self._REG_INT2_SRC)

    # ---- click detection -------------------------------------------------

    def set_click(self, single=0, double=0, threshold_g=0.5,
                  time_limit_ms=10, latency_ms=20, window_ms=50, latch=False):
        """Configure single / double click detection (``CLICK_*``).

        Args:
            single: Axis mask of single-click enables (X = 1, Y = 2,
                Z = 4).
            double: Axis mask of double-click enables (X = 1, Y = 2,
                Z = 4).
            threshold_g: Click threshold in *g* (same scale as
                ``INTx_THS``).
            time_limit_ms: Maximum time between the two clicks of a
                double click.
            latency_ms: Minimum time between two consecutive clicks.
            window_ms: Maximum time after the first click of a double
                click.
            latch: Keep the interrupt high until ``CLICK_SRC`` is read
                (``LIR_Click``).

        Raises:
            ValueError: If either axis mask is outside 0-7.
        """
        if single < 0 or single > 7 or double < 0 or double > 7:
            raise ValueError('single / double must be axis masks (0-7: X=1, Y=2, Z=4)')
        # Axis masks (X=1, Y=2, Z=4) map onto the per-axis XS/YS/ZS and
        # XD/YD/ZD enable bits of CLICK_CFG.
        cfg = ((single & 0x01) | ((single & 0x02) << 1) | ((single & 0x04) << 2) |
               ((double & 0x01) << 1) | ((double & 0x02) << 2) | ((double & 0x04) << 3))
        ths = self._threshold_code(threshold_g)
        self._write_reg(self._REG_CLICK_CFG, cfg)
        self._write_reg(self._REG_CLICK_THS, ths | (0x80 if latch else 0x00))
        self._write_reg(self._REG_TIME_LIMIT, self._duration_code(time_limit_ms, 127))
        self._write_reg(self._REG_TIME_LATENCY, self._duration_code(latency_ms, 255))
        self._write_reg(self._REG_TIME_WINDOW, self._duration_code(window_ms, 255))

    def read_click_source(self):
        """Read ``CLICK_SRC`` — **clears** the click interrupt.

        Returns:
            int: ``CLICK_SRC`` byte (``IA`` = bit 6, ``DCLICK`` = bit 5,
            ``SCLICK`` = bit 4, ``Sign`` = bit 3, axis flags in bits 2-0).
        """
        return self._read_reg(self._REG_CLICK_SRC)

    # ---- sleep-to-wake activity -----------------------------------------

    def set_activity(self, threshold_g, duration_ms):
        """Configure the sleep-to-wake / return-to-sleep activity function.

        While the function has dropped the chip into its 10 Hz low-power
        state the ``ODR``, ``LPen`` and ``HR`` settings are ignored; the
        chip restores them on activity. Pair with ``SOURCE_ACTIVITY``.

        Args:
            threshold_g: Activation threshold in *g* (``ACT_THS``).
            duration_ms: Activation duration in ms (``ACT_DUR``, where
                1 LSb = (8·ACT_DUR + 1)/ODR).

        Raises:
            ValueError: If the threshold rounds to 0 counts (``ACT_THS``
                must be non-zero).
        """
        code = self._threshold_code(threshold_g)
        if code == 0:
            raise ValueError('threshold_g is below 1 LSb — ACT_THS must be non-zero')
        dur = _round((duration_ms * self._rate_hz / 1000.0 - 1) / 8.0)
        dur = max(0, min(255, dur))
        self._write_reg(self._REG_ACT_THS, code)
        self._write_reg(self._REG_ACT_DUR, dur)

    def set_interrupt_polarity(self, active_low):
        """Set the shared INT1 / INT2 output polarity (``INT_POLARITY``).

        Args:
            active_low: True for active-low, False for active-high
                (the reset default).
        """
        ctrl6 = self._read_reg(self._REG_CTRL_REG6)
        if active_low:
            ctrl6 |= 0x02
        else:
            ctrl6 &= ~0x02
        self._write_reg(self._REG_CTRL_REG6, ctrl6)

    # ---- interrupt routing and delivery ----------------------------------

    def enable_interrupt(self, source, pin=1):
        """Route one interrupt source to INT`pin`.

        Only routes the source — the generator or function behind it is
        configured with the ``set_*`` methods. ``pin`` is ignored for
        INT1-only (data-ready, aux data-ready, watermark, overrun) and
        INT2-only (activity, boot) sources.

        Args:
            source: One of the :class:`LIS3DHSource` constants.
            pin: 1 or 2 (default 1).

        Raises:
            ValueError: If ``source`` is unknown or ``pin`` is not 1 or 2.
        """
        reg, mask, track = self._route(source, pin)
        value = self._read_reg(reg)
        self._write_reg(reg, value | mask)
        if track == 1:
            self._enabled1 |= 1 << self._source_bit(source)
        else:
            self._enabled2 |= 1 << self._source_bit(source)

    def disable_interrupt(self, source, pin=1):
        """Remove one interrupt source from INT`pin`.

        Args:
            source: One of the :class:`LIS3DHSource` constants.
            pin: 1 or 2 (default 1).

        Raises:
            ValueError: If ``source`` is unknown or ``pin`` is not 1 or 2.
        """
        reg, mask, track = self._route(source, pin)
        value = self._read_reg(reg)
        self._write_reg(reg, value & ~mask)
        if track == 1:
            self._enabled1 &= ~(1 << self._source_bit(source))
        else:
            self._enabled2 &= ~(1 << self._source_bit(source))

    def on_interrupt(self, callback, pin=1):
        """Subscribe to INT`pin` assertions.

        The callback receives the combined status int returned by
        :meth:`poll_interrupt`, and is only invoked for a non-zero
        status. Uses the connection's ``int_pin``; when no INT pin is
        wired the driver falls back to a 5 ms polling thread.

        Args:
            callback: Function called with the status int.
            pin: 1 or 2 (default 1) — the INT line the connection's
                ``int_pin`` is wired to.

        Raises:
            ValueError: If ``pin`` is not 1 or 2.
        """
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        self._int_callback = callback
        int_pin = getattr(self._connection, 'int_pin', None)
        if int_pin is not None and hasattr(int_pin, 'on_edge'):
            self._int_handler = lambda p: self._dispatch_int()
            int_pin.on_edge(self._int_handler, getattr(int_pin, 'FALLING', 2))
        else:
            self._start_polling_fallback()

    def off_interrupt(self, pin=1):
        """Unsubscribe from INT`pin` assertions.

        Args:
            pin: 1 or 2 (default 1).
        """
        self._int_callback = None
        int_pin = getattr(self._connection, 'int_pin', None)
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

    def poll_interrupt(self):
        """Read the source registers of every currently enabled source.

        **Reading ``INT1_SRC`` / ``INT2_SRC`` / ``CLICK_SRC`` clears
        their latched flags** (and the INT pin).

        Returns:
            int: Combined status int — bit 0 ``SOURCE_DATA_READY``,
            bit 1 ``SOURCE_AUX_DATA_READY``, bit 2 ``SOURCE_WATERMARK``,
            bit 3 ``SOURCE_OVERRUN``, bit 4 ``SOURCE_IA1``, bit 5
            ``SOURCE_IA2``, bit 6 ``SOURCE_CLICK``. Sources that are not
            enabled read as 0; ``SOURCE_ACTIVITY`` and ``SOURCE_BOOT``
            have no register flag and are never reported.
        """
        src = LIS3DHSource
        e1, e2 = self._enabled1, self._enabled2
        enabled = e1 | e2
        status = 0
        # INT1-only sources are routed through CTRL_REG3 (e1).
        if (e1 >> 0) & 1 and self._read_reg(self._REG_STATUS) & 0x08:
            status |= src.SOURCE_DATA_READY
        if (e1 >> 1) & 1 and self._read_reg(self._REG_STATUS_AUX) & 0x08:
            status |= src.SOURCE_AUX_DATA_READY
        if (e1 >> 2) & 1 or (e1 >> 3) & 1:
            fifo = self._read_reg(self._REG_FIFO_SRC)
            if ((e1 >> 2) & 1) and fifo & 0x80:
                status |= src.SOURCE_WATERMARK
            if ((e1 >> 3) & 1) and fifo & 0x40:
                status |= src.SOURCE_OVERRUN
        # Generator flags live in their own source register no matter
        # which pin the generator is routed to.
        if (enabled >> 4) & 1 and self._read_reg(self._REG_INT1_SRC) & 0x40:
            status |= src.SOURCE_IA1
        if (enabled >> 5) & 1 and self._read_reg(self._REG_INT2_SRC) & 0x40:
            status |= src.SOURCE_IA2
        if (enabled >> 6) & 1 and self._read_reg(self._REG_CLICK_SRC) & 0x40:
            status |= src.SOURCE_CLICK
        return status

    # ---- system ----------------------------------------------------------

    def reboot(self):
        """Reboot the memory content (``BOOT`` = 1) and re-run ``init``.

        Reloads the factory trim and waits 5 ms. The datasheet does not
        say whether the control registers survive, so the Initialization
        Sequence is re-run afterwards — configuration returns to the
        Minimal defaults.
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

    def set_sdo_pullup(self, connected):
        """Connect or disconnect the pull-up on SDO/SA0 (``CTRL_REG0``).

        Read-modify-write of bit 7 only; bit 4 is forced to 1 and the
        reserved bits stay 0 (``CTRL_REG0`` is not a normal register).

        Args:
            connected: True to connect the pull-up (``SDO_PU_DISC`` = 0),
                False to disconnect it.
        """
        value = 0x10 | (0x00 if connected else 0x80)
        self._write_reg(self._REG_CTRL_REG0, value)

    def self_test(self, mode=1):
        """Run the datasheet electrostatic self-test.

        Configures ±2 *g*, normal (10-bit) mode, 50 Hz with ``BDU`` on,
        averages a few samples with ``ST`` = 00, enables ``ST`` (01 =
        self-test 0, 10 = self-test 1), waits 2 samples and averages
        again. Every axis must show a 17-360 LSb change (1 LSb = 4 mg at
        10-bit / ±2 *g*). The previous ``CTRL_REG1`` / ``CTRL_REG4``
        configuration is restored afterwards.

        Args:
            mode: 1 = self-test 0, 2 = self-test 1 (default 1).

        Returns:
            bool: True if every axis showed a change inside 17-360 LSb.

        Raises:
            ValueError: If ``mode`` is not 1 or 2.
        """
        if mode not in (1, 2):
            raise ValueError('mode must be 1 (self-test 0) or 2 (self-test 1)')
        saved_ctrl1 = self._read_reg(self._REG_CTRL_REG1)
        saved_ctrl4 = self._read_reg(self._REG_CTRL_REG4)
        saved_range, saved_mode, saved_rate = self._range_g, self._mode, self._rate_hz
        sample_ms = 20  # 50 Hz

        def configure(st_bits):
            self._write_reg(self._REG_CTRL_REG4, 0x80 | st_bits)
            self._write_reg(self._REG_CTRL_REG1, 0x47)  # 50 Hz, LPen=0, X/Y/Z
            self._range_g, self._mode, self._rate_hz = _DEFAULT_RANGE_G, _MODE_NORMAL, 50

        try:
            configure(0x00)
            _delay_ms(20)
            before = self._average_raw(5, sample_ms)
            configure(0x02 if mode == 1 else 0x04)
            _delay_ms(40)  # 2 samples at 50 Hz
            after = self._average_raw(5, sample_ms)
        finally:
            self._write_reg(self._REG_CTRL_REG1, saved_ctrl1)
            self._write_reg(self._REG_CTRL_REG4, saved_ctrl4)
            self._range_g, self._mode, self._rate_hz = saved_range, saved_mode, saved_rate

        ok = True
        for i in range(3):
            delta = after[i] - before[i]
            if not (17 <= abs(delta) <= 360):
                ok = False
        return ok

    # ---- internal helpers ------------------------------------------------

    def _average_raw(self, samples, sample_ms):
        """Average `samples` raw readings per axis.

        Args:
            samples: Number of samples to average.
            sample_ms: Delay between the samples, in ms.

        Returns:
            list: The averaged (x, y, z) counts.
        """
        total = [0, 0, 0]
        for _ in range(samples):
            x, y, z = self.read_raw()
            total[0] += x
            total[1] += y
            total[2] += z
            _delay_ms(sample_ms)
        return [t / float(samples) for t in total]

    def _generator_regs(self, generator):
        """Return the (cfg, ths, dur) register addresses of a generator.

        Args:
            generator: 1 or 2.

        Returns:
            tuple: The three register addresses.

        Raises:
            ValueError: If ``generator`` is not 1 or 2.
        """
        if generator == 1:
            return (self._REG_INT1_CFG, self._REG_INT1_THS, self._REG_INT1_DUR)
        if generator == 2:
            return (self._REG_INT2_CFG, self._REG_INT2_THS, self._REG_INT2_DUR)
        raise ValueError('generator must be 1 or 2')

    def _threshold_code(self, threshold_g):
        """Convert a threshold in *g* into a 7-bit register code.

        Args:
            threshold_g: Threshold in *g*.

        Returns:
            int: The register code, clamped to 0-127.
        """
        lsb_mg = _THRESHOLD_LSB_MG[self._range_g]
        code = _round(threshold_g * 1000.0 / lsb_mg)
        return max(0, min(127, code))

    def _duration_code(self, duration_ms, limit=127):
        """Convert a duration in ms into a 1/ODR register code.

        Args:
            duration_ms: Duration in ms.
            limit: Maximum code value.

        Returns:
            int: The register code. While powered down there is no ODR
            to convert with, so the code is 0.
        """
        if self._rate_hz <= 0:
            return 0
        code = _round(duration_ms * self._rate_hz / 1000.0)
        return max(0, min(limit, code))

    def _source_bit(self, source):
        """Return the bit index of an interrupt source constant.

        Args:
            source: One of the :class:`LIS3DHSource` constants.

        Returns:
            int: The bit index (0-8).
        """
        bit = 0
        value = source
        while value > 1:
            value >>= 1
            bit += 1
        return bit

    def _route(self, source, pin):
        """Return (register, mask, tracked-pin) for an interrupt source.

        Args:
            source: One of the :class:`LIS3DHSource` constants.
            pin: 1 or 2.

        Returns:
            tuple: The routing register, its bit mask, and the pin the
            driver tracks the source on (1 or 2).

        Raises:
            ValueError: If the source is unknown or ``pin`` is invalid.
        """
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        entry = self._ROUTE.get(source)
        if entry is None:
            raise ValueError('unknown interrupt source: 0x{:04X}'.format(source))
        kind, mask = entry
        if kind == 'int1':
            return self._REG_CTRL_REG3, mask, 1
        if kind == 'int2':
            return self._REG_CTRL_REG6, mask, 2
        if pin == 1:
            return self._REG_CTRL_REG3, mask, 1
        return self._REG_CTRL_REG6, mask, 2

    def _dispatch_int(self):
        """Read the interrupt status and hand it to the callback."""
        callback = self._int_callback
        if callback is None:
            return
        try:
            status = self.poll_interrupt()
            if status:
                callback(status)
        except Exception:
            pass

    def _start_polling_fallback(self):
        """Poll the interrupt status every 5 ms when no INT pin is wired.

        The polling thread is stopped by :meth:`off_interrupt`.
        """
        try:
            import _thread
            import time
        except ImportError:
            return
        stop = [False]

        def _loop():
            while not stop[0]:
                self._dispatch_int()
                if hasattr(time, 'sleep_ms'):
                    time.sleep_ms(5)
                else:
                    time.sleep(0.005)

        _thread.start_new_thread(_loop, ())
        self._poll_thread = type('T', (), {'deinit': lambda self_: stop.__setitem__(0, True)})()
