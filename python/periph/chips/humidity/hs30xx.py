import math
import time


class HS30xxMinimal:
    """HS30xx humidity and temperature sensor — minimal interface.

    Covers the whole Renesas HS3xxx family (HS3001, HS3002, HS3003 — they differ
    only in accuracy class and housing, not in protocol). Each read() sends a
    measurement request (MR, an address-only write), waits 40 ms, fetches four
    bytes (data fetch, DF) and converts them. If the status bits report stale data
    it waits 10 ms and fetches once more.

    The constructor performs no bus access: bytes written within 10 ms of power-up
    could be taken as NVM programming commands.

    Default configuration (factory): 14-bit humidity and temperature resolution,
    fixed 40 ms wait after the request, no NVM access.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x44).
    """

    _CMD_ENTER_PROG = b'\xA0\x00\x00'
    _CMD_EXIT_PROG = b'\x80\x00\x00'

    _STATUS_VALID = 0
    _FULL_SCALE = 16383.0

    def __init__(self, connection):
        self._connection = connection
        self._wait = 0.04

    def _fetch(self, length):
        # MR is an address-only write (no data byte); the DF read follows after the conversion.
        self._connection.write(b'')
        time.sleep(self._wait)
        data = bytes(self._connection.read(length))
        if (data[0] >> 6) != self._STATUS_VALID:
            time.sleep(0.01)
            data = bytes(self._connection.read(length))
            if (data[0] >> 6) != self._STATUS_VALID:
                raise RuntimeError('HS30xx returned stale data')
        return data

    @staticmethod
    def _raw_humidity(data):
        return ((data[0] & 0x3F) << 8) | data[1]

    @staticmethod
    def _raw_temperature(data):
        return (data[2] << 6) | (data[3] >> 2)

    @classmethod
    def _to_humidity(cls, raw):
        return raw / cls._FULL_SCALE * 100.0

    @classmethod
    def _to_temperature(cls, raw):
        return raw / cls._FULL_SCALE * 165.0 - 40.0

    def read(self):
        """Measure temperature and humidity.

        Sends the measurement request, waits 40 ms, fetches 4 bytes and converts
        them (the output is always scaled to 14 bits).

        Returns:
            dict: {'temperature_c': float, 'humidity_pct': float}
                temperature_c: Temperature in degrees Celsius (-40 to 125 °C).
                humidity_pct: Relative humidity in percent (0 to 100 %RH).

        Raises:
            RuntimeError: If the data are still flagged stale after one retry.
        """
        data = self._fetch(4)
        return {'temperature_c': self._to_temperature(self._raw_temperature(data)),
                'humidity_pct': self._to_humidity(self._raw_humidity(data))}


class HS30xxFull(HS30xxMinimal):
    """HS30xx full interface — raw values, wait override, NVM resolution and ID, dew point.

    Extends HS30xxMinimal. The NVM methods (resolution, ID) need the sensor's
    programming mode, which can only be entered within 10 ms after power-up. They
    therefore take a ``power_cycle`` callable that cuts and restores the sensor
    supply (e.g. via a MOSFET or a GPIO powering the sensor); without it they raise.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x44).
    """

    _REG_RH_READ = 0x06
    _REG_RH_WRITE = 0x46
    _REG_T_READ = 0x11
    _REG_T_WRITE = 0x51
    _REG_ID_HIGH = 0x1E
    _REG_ID_LOW = 0x1F

    _NVM_OK = 0x81
    _RES_MASK = 0x0C00
    _BITS = (8, 10, 12, 14)

    def __init__(self, connection):
        super().__init__(connection)

    def read_temperature(self):
        """Measure temperature only (4-byte data fetch, humidity discarded).

        Returns:
            float: Temperature in degrees Celsius.

        Raises:
            RuntimeError: If the data are still flagged stale after one retry.
        """
        return self._to_temperature(self._raw_temperature(self._fetch(4)))

    def read_humidity(self):
        """Measure humidity only (2-byte data fetch).

        Returns:
            float: Relative humidity in percent (0 to 100 %RH).

        Raises:
            RuntimeError: If the data are still flagged stale after one retry.
        """
        return self._to_humidity(self._raw_humidity(self._fetch(2)))

    def read_raw(self):
        """Measure and return the raw 14-bit values and the status field, without a stale check.

        Returns:
            tuple: (raw_humidity, raw_temperature, status) — 14-bit values and the
                2-bit status (0 = valid, 1 = stale).
        """
        self._connection.write(b'')
        time.sleep(self._wait)
        data = bytes(self._connection.read(4))
        return (self._raw_humidity(data), self._raw_temperature(data), data[0] >> 6)

    def set_measurement_wait(self, ms):
        """Override the wait between measurement request and data fetch (default 40 ms).

        Lower resolutions convert faster: 0.1 ms + RH_time/2 + T_time/2 (datasheet).

        Args:
            ms: Wait in milliseconds, greater than 0.
        """
        if ms <= 0:
            raise ValueError('ms must be > 0')
        self._wait = ms / 1000.0

    def enter_programming_mode(self):
        """Send the enter-programming-mode command (0xA0 0x00 0x00) and wait 120 µs.

        Only valid within 10 ms after power-up; see get_resolution() for a managed sequence.
        """
        self._connection.write(self._CMD_ENTER_PROG)
        time.sleep(0.001)

    def exit_programming_mode(self):
        """Send the leave-programming-mode command (0x80 0x00 0x00) and return to measurement operation."""
        self._connection.write(self._CMD_EXIT_PROG)

    def read_nvm(self, reg):
        """Read one 16-bit NVM register (programming mode only).

        Args:
            reg: NVM read address (0x06, 0x11, 0x1E or 0x1F).

        Returns:
            int: Register value (0 to 65535).

        Raises:
            RuntimeError: If the response status byte is not 0x81.
        """
        self._connection.write(bytes([reg, 0x00, 0x00]))
        time.sleep(0.001)
        data = bytes(self._connection.read(3))
        if data[0] != self._NVM_OK:
            raise RuntimeError('HS30xx NVM read failed (status 0x%02X)' % data[0])
        return (data[1] << 8) | data[2]

    def _write_nvm(self, reg, value):
        self._connection.write(bytes([reg, (value >> 8) & 0xFF, value & 0xFF]))
        time.sleep(0.014)

    def _program(self, power_cycle):
        if power_cycle is None:
            raise RuntimeError('HS30xx NVM access needs a power_cycle callable')
        power_cycle()
        self.enter_programming_mode()

    def get_resolution(self, power_cycle=None):
        """Read the NVM resolution settings (power-cycles the sensor).

        Args:
            power_cycle: Callable that cuts and restores the sensor supply; the
                programming-mode command follows immediately.

        Returns:
            tuple: (humidity_bits, temperature_bits), each 8, 10, 12 or 14.

        Raises:
            RuntimeError: If power_cycle is None or the NVM read fails.
        """
        self._program(power_cycle)
        try:
            rh = self.read_nvm(self._REG_RH_READ)
            t = self.read_nvm(self._REG_T_READ)
        finally:
            self.exit_programming_mode()
        return (self._BITS[(rh >> 10) & 3], self._BITS[(t >> 10) & 3])

    def set_resolution(self, humidity_bits=None, temperature_bits=None, power_cycle=None):
        """Write the NVM resolution settings (power-cycles the sensor; bits 11:10 only).

        Each register is read, bits 11:10 replaced, and the whole word written
        back, then the driver waits 14 ms for the NVM commit.

        Args:
            humidity_bits: 8, 10, 12 or 14, or None to leave unchanged.
            temperature_bits: 8, 10, 12 or 14, or None to leave unchanged.
            power_cycle: Callable that cuts and restores the sensor supply.

        Raises:
            ValueError: If a value is not 8, 10, 12 or 14.
            RuntimeError: If power_cycle is None or an NVM read fails.
        """
        for bits in (humidity_bits, temperature_bits):
            if bits is not None and bits not in self._BITS:
                raise ValueError('resolution must be 8, 10, 12 or 14 bits')
        self._program(power_cycle)
        try:
            for bits, rreg, wreg in ((humidity_bits, self._REG_RH_READ, self._REG_RH_WRITE),
                                     (temperature_bits, self._REG_T_READ, self._REG_T_WRITE)):
                if bits is not None:
                    value = self.read_nvm(rreg)
                    value = (value & ~self._RES_MASK & 0xFFFF) | (self._BITS.index(bits) << 10)
                    self._write_nvm(wreg, value)
        finally:
            self.exit_programming_mode()

    def read_id(self, power_cycle=None):
        """Read the 32-bit sensor ID from NVM (power-cycles the sensor).

        Args:
            power_cycle: Callable that cuts and restores the sensor supply.

        Returns:
            int: 32-bit ID.

        Raises:
            RuntimeError: If power_cycle is None or the NVM read fails.
        """
        self._program(power_cycle)
        try:
            high = self.read_nvm(self._REG_ID_HIGH)
            low = self.read_nvm(self._REG_ID_LOW)
        finally:
            self.exit_programming_mode()
        return (high << 16) | low

    def dew_point(self):
        """Run one measurement and return the dew point (Magnus formula).

        Returns:
            float: Dew point in degrees Celsius. Returns -273.15 if humidity is 0 %RH.
        """
        m = self.read()
        rh = m['humidity_pct']
        if rh <= 0.0:
            return -273.15
        t = m['temperature_c']
        gamma = math.log(rh / 100.0) + 17.62 * t / (243.12 + t)
        return 243.12 * gamma / (17.62 - gamma)


# The HS3001, HS3002 and HS3003 share one protocol and differ only in accuracy
# class and housing, so they are plain aliases of the HS30xx drivers.
HS3001Minimal = HS3002Minimal = HS3003Minimal = HS30xxMinimal
HS3001Full = HS3002Full = HS3003Full = HS30xxFull
