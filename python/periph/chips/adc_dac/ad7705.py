class AD7705Minimal:
    """AD7705 2-channel, 16-bit sigma-delta ADC — minimal interface.

    Reads calibrated voltage from Channel 1 with sensible defaults. The driver
    uses the two-phase register-access protocol (Communication Register write
    followed by the data transfer within a single CS assertion) and polls
    `0/DRDY` over SPI rather than requiring a dedicated DRDY GPIO.

    Args:
        connection: Configured SPI connection (SPIConnection for the target platform).
        vref: Externally-supplied reference voltage in V (REF IN(+) − REF IN(−)).
        mclk_hz: Master clock frequency wired to MCLK IN. Must be one of
            1 000 000, 2 000 000, 2 457 600, or 4 915 200.
        reset_pin: Optional OutputPin for the chip's RESET line. When supplied,
            a hardware reset pulse is issued before configuration.
    """

    _REG_COMM        = 0x00
    _REG_SETUP       = 0x10
    _REG_CLOCK       = 0x20
    _REG_DATA        = 0x30
    _REG_TEST        = 0x40
    _REG_OFFSET      = 0x60
    _REG_GAIN        = 0x70

    _RW_WRITE = 0x00
    _RW_READ  = 0x08

    _CH1 = 0x00
    _CH2 = 0x01

    _MODE_NORMAL      = 0x00
    _MODE_SELF_CAL    = 0x40
    _MODE_ZERO_SYS    = 0x80
    _MODE_FULL_SYS    = 0xC0

    _GAIN_1   = 0x00
    _GAIN_2   = 0x08
    _GAIN_4   = 0x10
    _GAIN_8   = 0x18
    _GAIN_16  = 0x20
    _GAIN_32  = 0x28
    _GAIN_64  = 0x30
    _GAIN_128 = 0x38

    _BIPOLAR   = 0x00
    _UNIPOLAR  = 0x04

    _UNBUFFERED = 0x00
    _BUFFERED   = 0x02

    _FSYNC_RUN  = 0x00
    _FSYNC_HOLD = 0x01

    _STBY_RUN   = 0x00
    _STBY_SLEEP = 0x04

    _DRDY_MASK = 0x80

    _MCLK_VALID = (1_000_000, 2_000_000, 2_457_600, 4_915_200)

    _FS_RATES_1MHZ   = (20, 25, 100, 200)
    _FS_RATES_2_4MHZ = (50, 60, 250, 500)

    _GAIN_TO_BITS = {
        1: _GAIN_1, 2: _GAIN_2, 4: _GAIN_4, 8: _GAIN_8,
        16: _GAIN_16, 32: _GAIN_32, 64: _GAIN_64, 128: _GAIN_128,
    }

    def __init__(self, connection, vref, mclk_hz, reset_pin=None):
        """Initialize AD7705Minimal: configure Clock and Setup Registers and self-calibrate.

        Args:
            connection: Configured SPI connection.
            vref: Reference voltage in V.
            mclk_hz: Master clock frequency in Hz.
            reset_pin: Optional OutputPin driving RESET.

        Raises:
            ValueError: If mclk_hz is not one of the four supported frequencies.
        """
        if mclk_hz not in self._MCLK_VALID:
            raise ValueError("mclk_hz must be 1000000, 2000000, 2457600, or 4915200")
        self._conn = connection
        self._vref = float(vref)
        self._mclk_hz = mclk_hz
        self._reset_pin = reset_pin
        self._gain = 1
        self._bipolar = True
        self._buffered = False

        if reset_pin is not None:
            self._hardware_reset()

        self._configure_clock(self._FS_RATES_2_4MHZ[0] if mclk_hz >= 2_457_600 else self._FS_RATES_1MHZ[0])

        setup = self._MODE_SELF_CAL | self._GAIN_1 | self._BIPOLAR | self._UNBUFFERED | self._FSYNC_RUN
        self._write_reg_channel(self._REG_SETUP, setup, self._CH1, 1)
        self._wait_drdy()

    def _comm_byte(self, reg, rw, channel):
        """Build a Communication Register byte.

        Args:
            reg: One of the _REG_* constants.
            rw: _RW_WRITE or _RW_READ.
            channel: _CH1 or _CH2.

        Returns:
            int: Communication Register byte.
        """
        return reg | rw | (channel & 0x03)

    def _wait_drdy(self):
        """Poll Communication Register's 0/DRDY bit until the chip signals ready.

        Matches the datasheet's documented 3-wire SPI technique.
        """
        while True:
            raw = self._conn.write_read(bytes([self._comm_byte(self._REG_COMM, self._RW_READ, self._CH1)]), 1)
            if not (raw[0] & self._DRDY_MASK):
                return

    def _hardware_reset(self):
        """Pulse the RESET line low for >=100 ns (t2 min) to reset all registers.

        Requires a reset_pin OutputPin to have been supplied.
        """
        self._reset_pin.set(False)
        self._reset_pin.set(True)

    def _configure_clock(self, output_rate_hz):
        """Write the Clock Register with CLKDIV/CLK derived from mclk_hz.

        Args:
            output_rate_hz: One of the four rates in the family selected by mclk_hz.
        """
        if self._mclk_hz in (2_457_600, 4_915_200):
            clk_bit = 0x04
            rates = self._FS_RATES_2_4MHZ
        else:
            clk_bit = 0x00
            rates = self._FS_RATES_1MHZ
        try:
            fs_bits = rates.index(output_rate_hz)
        except ValueError:
            raise ValueError("output_rate_hz must be one of {} Hz".format(rates))
        clkdiv_bit = 0x08 if self._mclk_hz in (2_000_000, 4_915_200) else 0x00
        self._write_reg_channel(self._REG_CLOCK, clkdiv_bit | clk_bit | fs_bits, self._CH1, 1)

    def _write_reg_channel(self, reg, value, channel, n_bytes):
        """Write n_bytes to a register on the given channel.

        Sends the Communication Register byte and the data bytes in a single
        SPI write (CS held throughout).

        Args:
            reg: One of the _REG_* constants.
            value: Integer register value (only low n_bytes are sent).
            channel: _CH1 or _CH2.
            n_bytes: Number of data bytes (1 for Setup/Clock, 2 for Data, 3 for calibration).
        """
        comm = self._comm_byte(reg, self._RW_WRITE, channel)
        payload = bytearray([comm])
        for i in range(n_bytes - 1, -1, -1):
            payload.append((value >> (8 * i)) & 0xFF)
        self._conn.write(bytes(payload))

    def _read_reg_channel(self, reg, channel, n_bytes):
        """Read n_bytes from a register on the given channel.

        Sends the Communication Register byte (read direction) in a single
        write_read so CS is held throughout the two-phase protocol.

        Args:
            reg: One of the _REG_* constants.
            channel: _CH1 or _CH2.
            n_bytes: Number of data bytes (1, 2, or 3).

        Returns:
            int: Unsigned integer value assembled from the received bytes (big-endian).
        """
        comm = self._comm_byte(reg, self._RW_READ, channel)
        raw = self._conn.write_read(bytes([comm]), n_bytes)
        value = 0
        for b in raw:
            value = (value << 8) | b
        return value

    def read_raw(self):
        """Block until DRDY, then read and return the raw 16-bit Data Register code.

        Uses Channel 1 with the current gain/bipolar setting.

        Returns:
            int: Raw 16-bit code (0–65535).
        """
        self._wait_drdy()
        return self._read_reg_channel(self._REG_DATA, self._CH1, 2)

    def read_voltage(self):
        """Block until DRDY, then return the input voltage in V on Channel 1.

        Converts the raw 16-bit code to volts using gain 1 and bipolar coding
        (the Minimal default). Use AD7705Full for arbitrary gain/bipolar.

        Returns:
            float: Input voltage in V.
        """
        code = self.read_raw()
        return self._code_to_voltage(code, self._gain, self._bipolar)

    def _code_to_voltage(self, code, gain, bipolar):
        """Convert a raw 16-bit code to volts.

        Args:
            code: Raw 16-bit code (0–65535).
            gain: PGA gain (1/2/4/8/16/32/64/128).
            bipolar: True for bipolar (offset binary), False for unipolar.

        Returns:
            float: Voltage in V.
        """
        if bipolar:
            return ((code - 32768) / 32768.0) * (self._vref / gain)
        return (code / 65536.0) * (self._vref / gain)


class AD7705Full(AD7705Minimal):
    """AD7705 full interface — extends AD7705Minimal with full chip functionality.

    Adds per-channel configuration, all eight PGA gains, unipolar/bipolar
    selection, buffered/unbuffered analog inputs, all four output rates,
    self/system calibration, direct access to the 24-bit calibration
    coefficients, standby/wakeup power control, and optional hardware reset.

    Args:
        connection: Configured SPI connection (SPIConnection for the target platform).
        vref: Externally-supplied reference voltage in V.
        mclk_hz: Master clock frequency in Hz.
        reset_pin: Optional OutputPin for the chip's RESET line.
    """

    def configure(self, channel=1, gain=1, bipolar=True, buffered=False, output_rate_hz=50):
        """Write the Setup and Clock Registers for the given channel.

        Does not calibrate — call `self_calibrate(channel)` (or one of the
        system-calibration methods) afterward. The first `read_raw` after a
        configuration change may be stale; the datasheet recommends
        recalibration whenever gain, filter notch, or bipolar/unipolar mode
        changes.

        Args:
            channel: 1 or 2.
            gain: PGA gain — one of 1, 2, 4, 8, 16, 32, 64, 128.
            bipolar: True for bipolar (±V_REF/gain), False for unipolar (0 to +V_REF/gain).
            buffered: True to enable the analog input buffer, False to bypass it.
            output_rate_hz: One of the four rates in the family selected by mclk_hz.

        Raises:
            ValueError: If channel, gain, or output_rate_hz is invalid.
        """
        ch = self._validate_channel(channel)
        if gain not in self._GAIN_TO_BITS:
            raise ValueError("gain must be one of 1, 2, 4, 8, 16, 32, 64, 128")
        rate_family = self._FS_RATES_2_4MHZ if self._mclk_hz >= 2_457_600 else self._FS_RATES_1MHZ
        if output_rate_hz not in rate_family:
            raise ValueError("output_rate_hz must be one of {} Hz".format(rate_family))

        if self._mclk_hz in (2_457_600, 4_915_200):
            clk_bit = 0x04
            rates = self._FS_RATES_2_4MHZ
        else:
            clk_bit = 0x00
            rates = self._FS_RATES_1MHZ
        clkdiv_bit = 0x08 if self._mclk_hz in (2_000_000, 4_915_200) else 0x00
        fs_bits = rates.index(output_rate_hz)
        self._write_reg_channel(self._REG_CLOCK, clkdiv_bit | clk_bit | fs_bits, ch, 1)

        bu_bit = self._UNIPOLAR if not bipolar else self._BIPOLAR
        buf_bit = self._BUFFERED if buffered else self._UNBUFFERED
        setup = self._MODE_NORMAL | self._GAIN_TO_BITS[gain] | bu_bit | buf_bit | self._FSYNC_RUN
        self._write_reg_channel(self._REG_SETUP, setup, ch, 1)

        if channel == 1:
            self._gain = gain
            self._bipolar = bipolar
            self._buffered = buffered

    def _validate_channel(self, channel):
        """Validate and convert a channel number to its _CH* constant.

        Args:
            channel: 1 or 2.

        Returns:
            int: _CH1 or _CH2.

        Raises:
            ValueError: If channel is not 1 or 2.
        """
        if channel == 1:
            return self._CH1
        if channel == 2:
            return self._CH2
        raise ValueError("channel must be 1 or 2")

    def read_raw(self, channel=1):
        """Block until DRDY, then read the raw 16-bit Data Register code for the channel.

        Args:
            channel: 1 or 2 (default 1).

        Returns:
            int: Raw 16-bit code (0–65535).
        """
        ch = self._validate_channel(channel)
        self._wait_drdy()
        return self._read_reg_channel(self._REG_DATA, ch, 2)

    def read_voltage(self, channel=1):
        """Block until DRDY, then return the input voltage in V on the channel.

        Args:
            channel: 1 or 2 (default 1).

        Returns:
            float: Input voltage in V.
        """
        ch = self._validate_channel(channel)
        if channel == 1:
            code = self.read_raw_channel1()
            gain = self._gain
            bipolar = self._bipolar
        else:
            code = self.read_raw_channel2()
            gain = self._gain
            bipolar = self._bipolar
        return self._code_to_voltage(code, gain, bipolar)

    def read_raw_channel1(self):
        """Read the raw 16-bit Data Register code on Channel 1.

        Returns:
            int: Raw 16-bit code (0–65535).
        """
        self._wait_drdy()
        return self._read_reg_channel(self._REG_DATA, self._CH1, 2)

    def read_raw_channel2(self):
        """Read the raw 16-bit Data Register code on Channel 2.

        Returns:
            int: Raw 16-bit code (0–65535).
        """
        self._wait_drdy()
        return self._read_reg_channel(self._REG_DATA, self._CH2, 2)

    def self_calibrate(self, channel=1):
        """Run an internal self-calibration on the channel and block until done.

        Self-calibration duration is `6 × 1/output_rate` typical; DRDY returns
        ready after `9 × 1/output_rate + t_P`.

        Args:
            channel: 1 or 2 (default 1).
        """
        ch = self._validate_channel(channel)
        setup = self._MODE_SELF_CAL | self._GAIN_TO_BITS[self._gain] | (self._UNIPOLAR if not self._bipolar else self._BIPOLAR) | (self._BUFFERED if self._buffered else self._UNBUFFERED) | self._FSYNC_RUN
        self._write_reg_channel(self._REG_SETUP, setup, ch, 1)
        self._wait_drdy()

    def system_calibrate_zero(self, channel=1):
        """Run a zero-scale system calibration on the channel and block until done.

        The caller must present the zero-scale voltage at AIN before calling
        and hold it stable until this returns.

        Args:
            channel: 1 or 2 (default 1).
        """
        ch = self._validate_channel(channel)
        setup = self._MODE_ZERO_SYS | self._GAIN_TO_BITS[self._gain] | (self._UNIPOLAR if not self._bipolar else self._BIPOLAR) | (self._BUFFERED if self._buffered else self._UNBUFFERED) | self._FSYNC_RUN
        self._write_reg_channel(self._REG_SETUP, setup, ch, 1)
        self._wait_drdy()

    def system_calibrate_full(self, channel=1):
        """Run a full-scale system calibration on the channel and block until done.

        The caller must present the full-scale voltage at AIN before calling
        and hold it stable until this returns.

        Args:
            channel: 1 or 2 (default 1).
        """
        ch = self._validate_channel(channel)
        setup = self._MODE_FULL_SYS | self._GAIN_TO_BITS[self._gain] | (self._UNIPOLAR if not self._bipolar else self._BIPOLAR) | (self._BUFFERED if self._buffered else self._UNBUFFERED) | self._FSYNC_RUN
        self._write_reg_channel(self._REG_SETUP, setup, ch, 1)
        self._wait_drdy()

    def get_offset_calibration(self, channel=1):
        """Read the 24-bit Zero-Scale Calibration Register for the channel.

        Args:
            channel: 1 or 2 (default 1).

        Returns:
            int: 24-bit unsigned offset coefficient.
        """
        ch = self._validate_channel(channel)
        return self._read_reg_channel(self._REG_OFFSET, ch, 3)

    def set_offset_calibration(self, value, channel=1):
        """Write a 24-bit Zero-Scale Calibration Register for the channel.

        Restores a previously-captured offset coefficient without running
        a fresh calibration.

        Args:
            value: 24-bit unsigned offset coefficient.
            channel: 1 or 2 (default 1).
        """
        ch = self._validate_channel(channel)
        self._write_reg_channel(self._REG_OFFSET, value & 0xFFFFFF, ch, 3)

    def get_gain_calibration(self, channel=1):
        """Read the 24-bit Full-Scale Calibration Register for the channel.

        Args:
            channel: 1 or 2 (default 1).

        Returns:
            int: 24-bit unsigned gain coefficient.
        """
        ch = self._validate_channel(channel)
        return self._read_reg_channel(self._REG_GAIN, ch, 3)

    def set_gain_calibration(self, value, channel=1):
        """Write a 24-bit Full-Scale Calibration Register for the channel.

        Args:
            value: 24-bit unsigned gain coefficient.
            channel: 1 or 2 (default 1).
        """
        ch = self._validate_channel(channel)
        self._write_reg_channel(self._REG_GAIN, value & 0xFFFFFF, ch, 3)

    def standby(self):
        """Enter standby/power-down (~10 µA). Registers are retained.

        Sets STBY=1 in the Communication Register.
        """
        comm = self._comm_byte(self._REG_COMM, self._RW_WRITE, self._CH1) | self._STBY_SLEEP
        self._conn.write(bytes([comm]))

    def wakeup(self):
        """Exit standby and block until a fresh conversion is available.

        Clears STBY and waits for DRDY.
        """
        comm = self._comm_byte(self._REG_COMM, self._RW_WRITE, self._CH1) | self._STBY_RUN
        self._conn.write(bytes([comm]))
        self._wait_drdy()

    def reset(self):
        """Pulse the hardware RESET line (requires a reset_pin OutputPin).

        All registers return to power-on defaults. Re-run `configure` and a
        calibration afterward.

        Raises:
            RuntimeError: If no reset_pin was supplied to the constructor.
        """
        if self._reset_pin is None:
            raise RuntimeError("reset() requires a reset_pin to have been supplied at construction")
        self._hardware_reset()
