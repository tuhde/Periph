import math
import time


def _crc8(data):
    """CRC-8 used by the SHTC3: polynomial 0x31 (x^8 + x^5 + x^4 + 1), init 0xFF, no final XOR."""
    crc = 0xFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 0x80:
                crc = ((crc << 1) ^ 0x31) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
    return crc


class SHTC3Minimal:
    """SHTC3 humidity and temperature sensor — minimal interface.

    Each read() runs a full measurement cycle: wake-up (0x3517), a normal-mode
    temperature-first measurement without clock stretching (0x7866), a 13 ms
    wait, a 6-byte read with both CRC-8 bytes verified, and the sleep command
    (0xB098). A bad CRC is retried once before ValueError is raised.

    The device is command driven (no register pointer), so a plain I²C
    connection is used. The constructor does not touch the bus; init() performs
    the soft reset and the ID check.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x70).
    """

    _CMD_SLEEP = 0xB098
    _CMD_WAKE = 0x3517
    _CMD_RESET = 0x805D
    _CMD_READ_ID = 0xEFC8
    # measurement commands [low_power][clock_stretching], temperature first
    _CMD_MEASURE = ((0x7866, 0x7CA2), (0x609C, 0x6458))
    _ID_MASK = 0x083F
    _ID_VALUE = 0x0807
    _T_NORMAL = 0.013
    _T_LOW_POWER = 0.001

    def __init__(self, connection):
        self._connection = connection
        self._low_power = False
        self._stretching = False
        self._sleep_between = True
        self._asleep = False

    def _command(self, command):
        self._connection.write(bytes([(command >> 8) & 0xFF, command & 0xFF]))

    def _wake(self):
        if self._asleep:
            self._command(self._CMD_WAKE)
            time.sleep(0.001)
            self._asleep = False

    def _sleep(self):
        self._command(self._CMD_SLEEP)
        self._asleep = True

    def _read_word_with_crc(self, data, offset):
        chunk = data[offset:offset + 3]
        if _crc8(chunk[0:2]) != chunk[2]:
            raise ValueError('SHTC3 CRC mismatch')
        return (chunk[0] << 8) | chunk[1]

    def _measure(self):
        """One measurement cycle; returns (t_raw, rh_raw)."""
        self._wake()
        try:
            for attempt in (0, 1):
                self._command(self._CMD_MEASURE[1 if self._low_power else 0][1 if self._stretching else 0])
                if not self._stretching:
                    time.sleep(self._T_LOW_POWER if self._low_power else self._T_NORMAL)
                data = bytes(self._connection.read(6))
                try:
                    return (self._read_word_with_crc(data, 0), self._read_word_with_crc(data, 3))
                except ValueError:
                    if attempt:
                        raise
        finally:
            if self._sleep_between:
                self._sleep()

    @staticmethod
    def _to_humidity(code):
        return min(100.0, max(0.0, 100.0 * code / 65536.0))

    @staticmethod
    def _to_temperature(code):
        return -45.0 + 175.0 * code / 65536.0

    def init(self):
        """Wake, soft reset (0x805D), check the product ID, then sleep.

        Raises:
            ValueError: If the ID word fails its CRC or is not an SHTC3.
        """
        self._asleep = True
        self._wake()
        self._command(self._CMD_RESET)
        time.sleep(0.001)
        self._command(self._CMD_READ_ID)
        data = bytes(self._connection.read(3))
        ident = self._read_word_with_crc(data, 0)
        self._sleep()
        if (ident & self._ID_MASK) != self._ID_VALUE:
            raise ValueError('not an SHTC3 (ID 0x%04X)' % ident)

    def read(self):
        """Measure temperature and humidity.

        Returns:
            dict: {'temperature_c': float °C, 'humidity_pct': float %RH (0-100)}.

        Raises:
            ValueError: If a result fails its CRC check twice.
        """
        t, rh = self._measure()
        return {'temperature_c': self._to_temperature(t), 'humidity_pct': self._to_humidity(rh)}


class SHTC3Full(SHTC3Minimal):
    """SHTC3 full interface — single values, raw codes, low-power mode, clock stretching, sleep control, ID, dew point.

    Extends SHTC3Minimal with single values, raw codes, the low-power
    measurement mode, the clock-stretching command set, sleep control, soft
    reset, the product ID and the dew point.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x70).
    """

    def __init__(self, connection):
        super().__init__(connection)

    def read_temperature(self):
        """Run one measurement cycle and return the temperature.

        Returns:
            float: Temperature in degrees Celsius.
        """
        return self._to_temperature(self._measure()[0])

    def read_humidity(self):
        """Run one measurement cycle and return the humidity.

        Returns:
            float: Relative humidity in %RH, clamped to 0-100.
        """
        return self._to_humidity(self._measure()[1])

    def read_raw(self):
        """Run one measurement cycle and return the raw codes.

        Returns:
            tuple: (raw_temperature, raw_humidity), both 16-bit unsigned.
        """
        return self._measure()

    def set_low_power(self, on):
        """Use the low-power (0.7 ms) or the normal (10.8 ms) measurement commands.

        Args:
            on: True for low-power mode (waits 1 ms), False for normal mode (13 ms).
        """
        self._low_power = bool(on)

    def set_clock_stretching(self, on):
        """Use the clock-stretching command set; the fixed wait is skipped.

        Requires an I²C controller that tolerates a slave holding SCL low.

        Args:
            on: True for clock stretching, False for the no-stretching commands.
        """
        self._stretching = bool(on)

    def set_sleep_between(self, on):
        """Choose whether a read ends with the sleep command (default on).

        Args:
            on: True to sleep after every cycle, False to stay idle.
        """
        self._sleep_between = bool(on)

    def wake(self):
        """Send the wake-up command (0x3517) and wait 1 ms."""
        self._command(self._CMD_WAKE)
        time.sleep(0.001)
        self._asleep = False

    def sleep(self):
        """Send the sleep command (0xB098)."""
        self._sleep()

    def soft_reset(self):
        """Soft reset (0x805D), 1 ms wait; leaves the sensor idle."""
        self._wake()
        self._command(self._CMD_RESET)
        time.sleep(0.001)

    def read_id(self):
        """Read the 16-bit ID word (command 0xEFC8, CRC verified).

        A sleeping sensor is woken for the read and put back to sleep.

        Returns:
            int: ID word; (id & 0x083F) == 0x0807 for the SHTC3.

        Raises:
            ValueError: If the CRC does not match.
        """
        was_asleep = self._asleep
        self._wake()
        self._command(self._CMD_READ_ID)
        data = bytes(self._connection.read(3))
        if was_asleep:
            self._sleep()
        return self._read_word_with_crc(data, 0)

    def dew_point(self):
        """Run one measurement and compute the dew point (Magnus formula over water).

        Returns:
            float: Dew point in degrees Celsius.
        """
        m = self.read()
        t, rh = m['temperature_c'], m['humidity_pct']
        if rh <= 0.0:
            rh = 0.01
        g = math.log(rh / 100.0) + 17.62 * t / (243.12 + t)
        return 243.12 * g / (17.62 - g)
