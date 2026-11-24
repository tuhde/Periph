_SEGMENTS = {
    0: 'abcdef', 1: 'bc', 2: 'abdeg', 3: 'abcdg', 4: 'bcfg',
    5: 'acdfg', 6: 'acdefg', 7: 'abc', 8: 'abcdefg', 9: 'abcdfg',
}
_DEFAULT_MAP = {'a': 0, 'b': 1, 'c': 2, 'd': 3, 'e': 4, 'f': 5, 'g': 6, 'dp': 7}


class SAA1064Minimal:
    """SAA1064 4-digit LED driver — minimal interface.

    Drives four 7-segment digits (multiplexed in pairs, dynamic mode) with
    decimal points. I2C address 0x38-0x3B selected by the voltage on the ADR
    pin. Construction writes the control byte 0x47 (dynamic mode, all digits
    unblanked, 12 mA segment current) and blanks the four digits.

    Args:
        connection: Configured I2C connection pointing at the device.
    """

    DIGITS = 4

    _SUB_CONTROL = 0x00
    _C_DYNAMIC = 0x01
    _C_BLANK_13 = 0x02   # set = digits 1+3 NOT blanked
    _C_BLANK_24 = 0x04   # set = digits 2+4 NOT blanked
    _C_TEST = 0x08
    _C_CURRENT = 0x70

    def __init__(self, connection):
        self._connection = connection
        self._control = 0x47
        self._patterns = [0, 0, 0, 0]
        self._map = dict(_DEFAULT_MAP)
        self._glyphs = {}
        self._rebuild_glyphs()
        self._connection.write(bytes([self._SUB_CONTROL, self._control, 0, 0, 0, 0]))

    def _rebuild_glyphs(self):
        self._glyphs = {}
        for value, segs in _SEGMENTS.items():
            pattern = 0
            for s in segs:
                pattern |= 1 << self._map[s]
            self._glyphs[value] = pattern

    def _pattern(self, value):
        if value is None:
            return 0
        if value not in self._glyphs:
            raise ValueError('digit must be 0-9 or None')
        return self._glyphs[value]

    def _write_digits(self, first):
        n = self.DIGITS - first + 1
        self._connection.write(bytes([first] + self._patterns[first - 1:first - 1 + n]))

    def show_digits(self, digits):
        """Show four digits using the segment mapping.

        Args:
            digits: Four values, each 0-9 or None (blank).
        """
        if len(digits) != self.DIGITS:
            raise ValueError('digits must have 4 values')
        patterns = [self._pattern(d) for d in digits]
        self._patterns = patterns
        self._write_digits(1)

    def show_number(self, value):
        """Show a right-aligned decimal number with leading zeros blanked.

        Args:
            value: Integer 0-9999.
        """
        if value < 0 or value > 9999:
            raise ValueError('value must be in 0..9999')
        digits = [None, None, None, value % 10]
        value //= 10
        for i in (2, 1, 0):
            if value > 0:
                digits[i] = value % 10
                value //= 10
        self.show_digits(digits)

    def clear(self):
        """Blank all four digits."""
        self._patterns = [0, 0, 0, 0]
        self._write_digits(1)

    def write_raw(self, patterns, first_digit=1):
        """Write raw segment bytes starting at a digit.

        Args:
            patterns: 1-4 segment bytes (bit 0 = P1/P9 ... bit 7 = P8/P16).
            first_digit: First digit to write, 1-4.
        """
        patterns = list(patterns)
        if first_digit < 1 or first_digit > self.DIGITS:
            raise ValueError('first_digit must be in 1..4')
        if len(patterns) < 1 or first_digit - 1 + len(patterns) > self.DIGITS:
            raise ValueError('patterns must fit within digits 1..4')
        for p in patterns:
            if p < 0 or p > 255:
                raise ValueError('pattern must be 0..255')
        for i, p in enumerate(patterns):
            self._patterns[first_digit - 1 + i] = p
        self._connection.write(bytes([first_digit] + patterns))


class SAA1064Full(SAA1064Minimal):
    """SAA1064 full interface — mode, blanking, current, test, segment map, status.

    Args:
        connection: Configured I2C connection pointing at the device.
    """

    _CURRENTS = (0, 3, 6, 9, 12, 15, 18, 21)

    def _write_control(self):
        self._connection.write(bytes([self._SUB_CONTROL, self._control]))

    def _set_bit(self, mask, on):
        if on:
            self._control |= mask
        else:
            self._control &= ~mask & 0x7F
        self._write_control()

    def set_mode(self, dynamic):
        """Select dynamic (all four digits) or static (digits 1 and 2) mode.

        Args:
            dynamic: True for dynamic mode, False for static mode.
        """
        self._set_bit(self._C_DYNAMIC, dynamic)

    def blank(self, digits_13, digits_24):
        """Blank digit pairs.

        Args:
            digits_13: True to blank digits 1 and 3.
            digits_24: True to blank digits 2 and 4.
        """
        if digits_13:
            self._control &= ~self._C_BLANK_13 & 0x7F
        else:
            self._control |= self._C_BLANK_13
        if digits_24:
            self._control &= ~self._C_BLANK_24 & 0x7F
        else:
            self._control |= self._C_BLANK_24
        self._write_control()

    def set_current(self, ma):
        """Set the segment output current.

        Args:
            ma: 0, 3, 6, 9, 12, 15, 18 or 21 (mA).
        """
        if ma not in self._CURRENTS:
            raise ValueError('ma must be one of 0, 3, 6, 9, 12, 15, 18, 21')
        self._control = (self._control & ~self._C_CURRENT & 0x7F) | ((ma // 3) << 4)
        self._write_control()

    def segment_test(self, on):
        """Switch all segment outputs on (segment test).

        Args:
            on: True to start the test, False to stop it.
        """
        self._set_bit(self._C_TEST, on)

    def set_segment_map(self, mapping):
        """Remap the helper's segment bits to the module wiring.

        Args:
            mapping: Dict with keys 'a'-'g' and 'dp', values bit numbers 0-7.
        """
        for key in _DEFAULT_MAP:
            if key not in mapping:
                raise ValueError('mapping needs keys a-g and dp')
            if mapping[key] < 0 or mapping[key] > 7:
                raise ValueError('bit numbers must be in 0..7')
        self._map = dict((k, mapping[k]) for k in _DEFAULT_MAP)
        self._rebuild_glyphs()

    def show_digit(self, position, value, dp=False):
        """Show one digit with an optional decimal point.

        Args:
            position: Digit 1-4.
            value: 0-9 or None (blank).
            dp: True to light the decimal point.
        """
        if position < 1 or position > self.DIGITS:
            raise ValueError('position must be in 1..4')
        pattern = self._pattern(value)
        if dp:
            pattern |= 1 << self._map['dp']
        self._patterns[position - 1] = pattern
        self._connection.write(bytes([position, pattern]))

    def set_decimal_point(self, position, on):
        """Switch a decimal point, keeping the cached segments.

        Args:
            position: Digit 1-4.
            on: True to light the decimal point.
        """
        if position < 1 or position > self.DIGITS:
            raise ValueError('position must be in 1..4')
        bit = 1 << self._map['dp']
        if on:
            self._patterns[position - 1] |= bit
        else:
            self._patterns[position - 1] &= ~bit & 0xFF
        self._connection.write(bytes([position, self._patterns[position - 1]]))

    def read_status(self):
        """Read the status byte (bit 7 = PR power-reset flag, cleared by the read).

        Returns:
            The status byte, 0-255.
        """
        return self._connection.read(1)[0]

    def power_reset_flag(self):
        """Read the status byte and return its PR flag.

        Returns:
            True if a power reset occurred since the status was last read.
        """
        return (self.read_status() & 0x80) != 0

    def write_control(self, value):
        """Write a raw control byte (bit 7 forced to 0).

        Args:
            value: Control byte 0-127.
        """
        self._control = value & 0x7F
        self._write_control()
