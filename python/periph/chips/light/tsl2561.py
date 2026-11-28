"""TSL2561 — light-to-digital converter with two photodiode channels (TAOS / ams).

Channel 0 responds to visible and infrared light, channel 1 mainly to
infrared. The two 16-bit integrating ADC counts are combined with the
datasheet's empirical formula into an illuminance in lux that approximates
the human eye response. Gain is 1× or 16×, the integration time 13.7 ms,
101 ms or 402 ms (or host-timed manual integration), and a channel-0
threshold window drives the open-drain INT pin. The I²C address is 0x29
(ADDR SEL = GND), 0x39 (floating) or 0x49 (VDD). The same driver file is used
on MicroPython, CircuitPython, and Linux hosts.

Every register access starts with a command byte (``0x80 | address`` for a
byte, ``0xA0 | address`` for a little-endian word); the driver uses
``RegisterConnection`` with the command byte as the register and reads both
channels in one 4-byte block read (``0x9C``) so the pair belongs to one
integration.

Args:
    connection: RegisterConnection (I²C) pointing at the device (0x29, 0x39 or 0x49).
"""

import time

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False

I2C_ADDRESS = 0x39
I2C_ADDRESS_GND = 0x29
I2C_ADDRESS_VDD = 0x49

_CMD = 0x80
_CMD_CLEAR = 0xC0
_CMD_WORD = 0xA0
_CMD_DATA_BLOCK = 0x9C

_REG_CONTROL = 0x0
_REG_TIMING = 0x1
_REG_THRESH_LOW = 0x2
_REG_THRESH_HIGH = 0x4
_REG_INTERRUPT = 0x6
_REG_ID = 0xA

_POWER_ON = 0x03
_POWER_OFF = 0x00

_TIMING_GAIN = 0x10
_TIMING_MANUAL = 0x08
_TIMING_INTEG_MASK = 0x03
_INTEG_MANUAL = 0x03

# INTEG code -> (nominal ms reported by the API, scale, full-scale count, settle wait ms).
# The settle wait is one integration plus the ±6 % oscillator tolerance and margin.
_INTEG = {
    0: (14, 0.034, 5047, 20),
    1: (101, 0.252, 37177, 120),
    2: (402, 1.0, 65535, 450),
}
_INTEG_BY_MS = {14: 0, 101: 1, 402: 2}

# (gain, INTEG) from most to least sensitive, used by auto_range().
_RANGES = ((16, 2), (1, 2), (1, 1), (1, 0))
_AUTO_MIN_COUNTS = 100

# Lux coefficient sets: (r limit, a, b, uses ch1) per segment; lux = a*ch0n - b*ch0n*r^1.4
# for the first segment, a*ch0n - b*ch1n for the others; r > 1.30 gives 0.
_COEFF_TFNCL = ((0.50, 0.0304, 0.062), (0.61, 0.0224, 0.031),
                (0.80, 0.0128, 0.0153), (1.30, 0.00146, 0.00112))
_COEFF_CS = ((0.52, 0.0315, 0.0593), (0.65, 0.0229, 0.0291),
             (0.80, 0.0157, 0.0180), (1.30, 0.00338, 0.00260))
_PACKAGES = {'T': _COEFF_TFNCL, 'FN': _COEFF_TFNCL, 'CL': _COEFF_TFNCL, 'CS': _COEFF_CS}

# Multiplier for every integration wait; tests set it to 0.0 to skip the sleeps.
_WAIT_FACTOR = 1.0


def _sleep_ms(ms):
    if _WAIT_FACTOR > 0 and ms > 0:
        time.sleep(ms * _WAIT_FACTOR / 1000.0)


def calculate_lux(ch0, ch1, gain=1, integration_ms=402, package='T'):
    """Convert raw channel counts into lux with the datasheet's empirical formula.

    Args:
        ch0: Channel 0 count (visible + infrared).
        ch1: Channel 1 count (infrared).
        gain: 1 or 16.
        integration_ms: 14, 101 or 402 (any other value is treated as 402 ms).
        package: ``"T"``, ``"FN"``, ``"CL"`` or ``"CS"``.

    Returns:
        float: Illuminance in lux; 0.0 for ``ch0 == 0`` or a ratio above 1.30.
    """
    code = _INTEG_BY_MS.get(integration_ms, 2)
    scale = _INTEG[code][1]
    factor = (16.0 / gain) / scale
    ch0n = ch0 * factor
    ch1n = ch1 * factor
    if ch0n <= 0:
        return 0.0
    r = ch1n / ch0n
    coeff = _PACKAGES[package]
    if r <= coeff[0][0]:
        lux = coeff[0][1] * ch0n - coeff[0][2] * ch0n * (r ** 1.4)
    elif r <= coeff[1][0]:
        lux = coeff[1][1] * ch0n - coeff[1][2] * ch1n
    elif r <= coeff[2][0]:
        lux = coeff[2][1] * ch0n - coeff[2][2] * ch1n
    elif r <= coeff[3][0]:
        lux = coeff[3][1] * ch0n - coeff[3][2] * ch1n
    else:
        lux = 0.0
    return max(0.0, lux)


class TSL2561Minimal:
    """TSL2561 two-channel ambient light sensor — minimal interface.

    Powers the chip up with 1× gain and 402 ms integration (the reset timing)
    and reads the illuminance in lux with the T/FN/CL package coefficients.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    def __init__(self, connection):
        """Initialize TSL2561Minimal and run the initialization sequence.

        Writes CONTROL ← 0x03 (power up), reads it back to verify the bus,
        writes TIMING ← 0x02 (1×, 402 ms) and waits one integration (450 ms)
        so the first ``read_lux()`` returns a real measurement.

        Args:
            connection: RegisterConnection (I²C) pointing at the device.

        Raises:
            OSError: if CONTROL does not read back as powered up.
        """
        self._connection = connection
        self._gain = 1
        self._integ = 2
        self._package = 'T'
        self._write_byte(_REG_CONTROL, _POWER_ON)
        if self._read_byte(_REG_CONTROL) & 0x03 != _POWER_ON:
            raise OSError('TSL2561: CONTROL did not read back as powered up')
        self._write_timing()
        _sleep_ms(_INTEG[self._integ][3])

    def _read_byte(self, reg):
        return self._connection.read_reg(_CMD | reg, 1)[0]

    def _write_byte(self, reg, value):
        self._connection.write_reg(_CMD | reg, bytes([value & 0xFF]))

    def _read_word(self, reg):
        data = self._connection.read_reg(_CMD_WORD | reg, 2)
        return data[0] | (data[1] << 8)

    def _write_word(self, reg, value):
        self._connection.write_reg(_CMD_WORD | reg, bytes([value & 0xFF, (value >> 8) & 0xFF]))

    def _write_timing(self, manual=False):
        value = (_TIMING_GAIN if self._gain == 16 else 0) | self._integ
        if manual:
            value |= _TIMING_MANUAL
        self._write_byte(_REG_TIMING, value)

    def _read_channels(self):
        data = self._connection.read_reg(_CMD_DATA_BLOCK, 4)
        return data[0] | (data[1] << 8), data[2] | (data[3] << 8)

    def _full_scale(self):
        return _INTEG[self._integ][2] if self._integ in _INTEG else 65535

    def _integration_ms(self):
        return _INTEG[self._integ][0] if self._integ in _INTEG else 0

    def _lux_from(self, ch0, ch1):
        limit = self._full_scale()
        if ch0 >= limit or ch1 >= limit:
            raise ValueError('TSL2561: channel saturated (ch0=%d, ch1=%d)' % (ch0, ch1))
        return calculate_lux(ch0, ch1, self._gain, self._integration_ms(), self._package)

    def read_lux(self):
        """Read the illuminance.

        Returns:
            float: Illuminance in lux (T/FN/CL coefficients unless changed by
            the Full driver); 0.0 for a channel ratio above 1.30.

        Raises:
            ValueError: if a channel is saturated (count at or above the
                full-scale limit of the integration time).
        """
        ch0, ch1 = self._read_channels()
        return self._lux_from(ch0, ch1)


class TSL2561Full(TSL2561Minimal):
    """TSL2561 full interface — extends Minimal with raw counts, gain and
    integration time, package coefficients, auto-ranging, power control,
    manual integration, the interrupt window and interrupt delivery.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    def __init__(self, connection):
        TSL2561Minimal.__init__(self, connection)
        self._callback = None
        self._poll_thread = None
        self._poll_stop = True
        self._int_source = None

    def read_raw(self):
        """Read both ADC channels in one block read.

        Returns:
            tuple: ``(ch0, ch1)`` — channel 0 (visible + IR) and channel 1 (IR) counts.
        """
        return self._read_channels()

    def set_gain(self, gain):
        """Set the ADC gain (TIMING.GAIN).

        The conversion in progress still uses the old setting; wait one
        integration time before trusting the next reading.

        Args:
            gain: 1 (low) or 16 (high).

        Raises:
            ValueError: on any other value.
        """
        if gain not in (1, 16):
            raise ValueError('gain must be 1 or 16')
        self._gain = gain
        self._write_timing()

    def get_gain(self):
        """Return the current ADC gain.

        Returns:
            int: 1 or 16.
        """
        return self._gain

    def set_integration_time(self, ms):
        """Set the integration time (TIMING.INTEG).

        The conversion in progress still uses the old setting; wait one
        integration time before trusting the next reading.

        Args:
            ms: 14 (13.7 ms), 101 or 402.

        Raises:
            ValueError: on any other value.
        """
        if ms not in _INTEG_BY_MS:
            raise ValueError('integration time must be 14, 101 or 402 ms')
        self._integ = _INTEG_BY_MS[ms]
        self._write_timing()

    def get_integration_time(self):
        """Return the current integration time.

        Returns:
            int: 14, 101 or 402 ms; 0 in manual timing mode.
        """
        return self._integration_ms()

    def set_package(self, package):
        """Select the lux coefficient set.

        Args:
            package: ``"T"``, ``"FN"``, ``"CL"`` (shared coefficients) or ``"CS"``.

        Raises:
            ValueError: on any other value.
        """
        if package not in _PACKAGES:
            raise ValueError('package must be T, FN, CL or CS')
        self._package = package

    def read_lux_raw(self):
        """Read lux and both channel counts from one sample.

        Returns:
            tuple: ``(lux, ch0, ch1)``.

        Raises:
            ValueError: if a channel is saturated.
        """
        ch0, ch1 = self._read_channels()
        return self._lux_from(ch0, ch1), ch0, ch1

    def _apply_range(self, index):
        old_wait = _INTEG[self._integ][3] if self._integ in _INTEG else 0
        self._gain, self._integ = _RANGES[index]
        self._write_timing()
        _sleep_ms(old_wait + _INTEG[self._integ][3])

    def auto_range(self):
        """Pick gain and integration time for the current light level and read lux.

        Steps through 16×/402 ms, 1×/402 ms, 1×/101 ms and 1×/13.7 ms until
        neither channel saturates and channel 0 is above 100 counts (or the
        end of the ladder is reached), waiting for a fresh integration after
        every change. The chosen setting stays active.

        Returns:
            float: Illuminance in lux.

        Raises:
            ValueError: if a channel still saturates at 1×/13.7 ms.
        """
        current = (self._gain, self._integ)
        if current in _RANGES:
            index = _RANGES.index(current)
        else:
            self._apply_range(1)
            index = 1
        for _ in range(2 * len(_RANGES)):
            ch0, ch1 = self._read_channels()
            limit = self._full_scale()
            if (ch0 >= limit or ch1 >= limit) and index < len(_RANGES) - 1:
                index += 1
            elif ch0 < _AUTO_MIN_COUNTS and ch0 < limit and ch1 < limit and index > 0:
                index -= 1
            else:
                return self._lux_from(ch0, ch1)
            self._apply_range(index)
        ch0, ch1 = self._read_channels()
        return self._lux_from(ch0, ch1)

    def power_up(self):
        """Power the chip up (CONTROL ← 0x03); integration restarts."""
        self._write_byte(_REG_CONTROL, _POWER_ON)

    def power_down(self):
        """Power the chip down (CONTROL ← 0x00); the registers keep their values."""
        self._write_byte(_REG_CONTROL, _POWER_OFF)

    def is_powered(self):
        """Report whether the chip is powered up.

        Returns:
            bool: True if CONTROL reads 0x03.
        """
        return self._read_byte(_REG_CONTROL) & 0x03 == _POWER_ON

    def start_manual(self):
        """Start a host-timed integration (INTEG = 11, MANUAL = 1).

        ``read_lux()`` in manual mode uses the 402 ms scale; use
        ``read_raw()`` and scale the counts by the actual integration time.
        """
        self._integ = _INTEG_MANUAL
        self._write_timing(manual=True)

    def stop_manual(self):
        """Stop the host-timed integration (INTEG = 11, MANUAL = 0); read the counts next."""
        self._integ = _INTEG_MANUAL
        self._write_timing(manual=False)

    def set_thresholds(self, low, high):
        """Set the channel-0 interrupt window (word writes of 0x2–0x3 and 0x4–0x5).

        Args:
            low: Low threshold, channel-0 counts 0–65535.
            high: High threshold, channel-0 counts 0–65535.

        Raises:
            ValueError: if a value is outside 0–65535.
        """
        for value in (low, high):
            if not 0 <= value <= 0xFFFF:
                raise ValueError('threshold must be 0-65535')
        self._write_word(_REG_THRESH_LOW, low)
        self._write_word(_REG_THRESH_HIGH, high)

    def get_thresholds(self):
        """Read the channel-0 interrupt window.

        Returns:
            tuple: ``(low, high)`` in channel-0 counts.
        """
        return self._read_word(_REG_THRESH_LOW), self._read_word(_REG_THRESH_HIGH)

    def configure_interrupt(self, mode, persist=0):
        """Configure the INTERRUPT register.

        Args:
            mode: 0 disabled, 1 level interrupt, 2 SMBAlert style.
            persist: 0 every cycle, 1 any value outside the window, N (2–15)
                N consecutive cycles outside the window.

        Raises:
            ValueError: if ``mode`` is not 0–2 or ``persist`` not 0–15.
        """
        if mode not in (0, 1, 2):
            raise ValueError('mode must be 0, 1 or 2')
        if not 0 <= persist <= 15:
            raise ValueError('persist must be 0-15')
        self._write_byte(_REG_INTERRUPT, (mode << 4) | persist)

    def read_id(self):
        """Read the ID register.

        Returns:
            int: ID byte; ``(id >> 4) == 1`` for the TSL2561, bits 3:0 the revision.
        """
        return self._read_byte(_REG_ID)

    def poll_interrupt(self):
        """Check channel 0 against the programmed window.

        The persistence filter is not modelled and the chip's interrupt is not
        cleared.

        Returns:
            bool: True if channel 0 is ≤ the low or > the high threshold.
        """
        ch0, _ = self._read_channels()
        low, high = self.get_thresholds()
        return ch0 <= low or ch0 > high

    def clear_interrupt(self):
        """Send the command byte 0xC0 (CMD + CLEAR) — releases a level interrupt."""
        self._connection.write(bytes([_CMD_CLEAR]))

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to INT (falling edge).

        ``callback(channel0)`` receives the current channel-0 count. With a
        pin the driver clears the level interrupt after the callback so the
        next edge can occur; without one a polling thread (Linux) calls
        ``poll_interrupt()`` once per integration time.

        Args:
            callback: Callable(channel0: int).
            int_pin: Optional InputPin overriding connection.int_pin.
        """
        self._callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if pin is not None:
            from periph.connection.input_pin import InputPin
            pin.on_edge(self._int_handler, InputPin.FALLING)
            self._int_source = pin
        elif _LINUX:
            self._poll_stop = False
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()

    def off_interrupt(self):
        """Unsubscribe and stop delivery."""
        self._poll_stop = True
        src = self._int_source
        if src is not None:
            src.off_edge(self._int_handler)
            self._int_source = None
        self._callback = None

    def _int_handler(self):
        ch0, _ = self._read_channels()
        if self._callback:
            self._callback(ch0)
        self.clear_interrupt()

    def _poll_loop(self):
        while not self._poll_stop:
            if self.poll_interrupt() and self._callback:
                ch0, _ = self._read_channels()
                self._callback(ch0)
            time.sleep(max(self._integration_ms(), 14) / 1000.0)
