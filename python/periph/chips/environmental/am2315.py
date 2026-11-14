import time


def _crc16(data):
    """CRC-16 (Modbus) used by the AM2315: init 0xFFFF, polynomial 0xA001 (reflected)."""
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 1:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return crc


def _now_ms():
    if hasattr(time, 'ticks_ms'):
        return time.ticks_ms()
    return int(time.monotonic() * 1000)


def _since_ms(start):
    if hasattr(time, 'ticks_diff'):
        return time.ticks_diff(time.ticks_ms(), start)
    return int(time.monotonic() * 1000) - start


class AM2315Error(Exception):
    """The AM2315 answered with an error response (function code with bit 7 set).

    Attributes:
        code: The error response code, 0x80 (function not supported), 0x81 (illegal
            read address), 0x82 (write data out of range), 0x83 (CRC error) or
            0x84 (write disabled).
    """

    def __init__(self, code):
        Exception.__init__(self, 'AM2315 error response 0x%02X' % code)
        self.code = code


class AM2315Minimal:
    """AM2315 humidity and temperature sensor — minimal interface.

    The sensor speaks a Modbus-style protocol: every read is a wake-up (a write the
    sensor does not acknowledge), a command write, a 2 ms wait and a read whose
    response carries a CRC-16. The sensor measures after each communication and
    returns that result on the next one, so read() returns the previous measurement;
    transactions are kept at least 2 s apart by sleeping the remainder.

    Default configuration (baked in at construction): the constructor does not touch
    the bus; the first read() waits until 2 s after construction (power-up settling);
    one retry after a new wake on a CRC mismatch or bus error.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x5C,
            standard mode, at most 100 kHz).
    """

    _FUNC_READ = 0x03
    _FUNC_WRITE = 0x10
    _MIN_INTERVAL_MS = 2000

    def __init__(self, connection):
        self._connection = connection
        self._interval_ms = self._MIN_INTERVAL_MS
        self._last = _now_ms()

    def _wait_interval(self):
        if self._interval_ms <= 0:
            return
        remaining = self._interval_ms - _since_ms(self._last)
        if remaining > 0:
            time.sleep(remaining / 1000.0)

    def _wake(self):
        try:
            self._connection.write(bytes([0x00]))      # the sleeping sensor NACKs this
        except OSError:
            pass
        time.sleep(0.001)

    def _exchange(self, frame, response_len):
        self._wait_interval()
        self._wake()
        self._connection.write(bytes(frame))
        time.sleep(0.002)
        data = bytes(self._connection.read(response_len))
        self._last = _now_ms()
        return data

    def _check(self, data, function, response_len):
        if len(data) >= 1 and data[0] & 0x80:
            raise AM2315Error(data[0])
        if len(data) < response_len:
            raise OSError('AM2315 short response')
        if data[0] != function:
            raise ValueError('AM2315 unexpected function code')
        crc = data[response_len - 2] | (data[response_len - 1] << 8)
        if _crc16(data[:response_len - 2]) != crc:
            raise ValueError('AM2315 CRC mismatch')

    def _with_retry(self, fn):
        try:
            return fn()
        except (OSError, ValueError):
            return fn()

    def _read_registers(self, start, count):
        def once():
            data = self._exchange([self._FUNC_READ, start, count], count + 4)
            self._check(data, self._FUNC_READ, count + 4)
            if data[1] != count:
                raise ValueError('AM2315 unexpected byte count')
            return data[2:2 + count]
        return self._with_retry(once)

    @staticmethod
    def _to_humidity(raw):
        return raw / 10.0

    @staticmethod
    def _to_temperature(raw):
        t = (raw & 0x7FFF) / 10.0
        return -t if raw & 0x8000 else t

    def read(self):
        """Read temperature and humidity (registers 0x00–0x03).

        Returns the previous measurement; see read_fresh() on AM2315Full for the
        real-time value. Sleeps so that transactions stay at least 2 s apart.

        Returns:
            dict: {'temperature_c': float, 'humidity_pct': float}

        Raises:
            ValueError: If the CRC mismatches twice.
            OSError: If the bus fails twice.
            AM2315Error: If the sensor returns an error response.
        """
        d = self._read_registers(0x00, 4)
        return {'temperature_c': self._to_temperature((d[2] << 8) | d[3]),
                'humidity_pct': self._to_humidity((d[0] << 8) | d[1])}


class AM2315Full(AM2315Minimal):
    """AM2315 full interface — single values, raw words, fresh read, registers, user registers.

    Extends AM2315Minimal with temperature-only and humidity-only reads, raw words,
    the two-read real-time value, arbitrary register read/write (CRC verified),
    device information, the user registers and an explicit wake-up.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x5C).
    """

    def __init__(self, connection):
        super().__init__(connection)

    def read_temperature(self):
        """Read the temperature only (registers 0x02–0x03).

        Returns:
            float: Temperature in degrees Celsius.
        """
        d = self._read_registers(0x02, 2)
        return self._to_temperature((d[0] << 8) | d[1])

    def read_humidity(self):
        """Read the humidity only (registers 0x00–0x01).

        Returns:
            float: Relative humidity in %RH.
        """
        d = self._read_registers(0x00, 2)
        return self._to_humidity((d[0] << 8) | d[1])

    def read_raw(self):
        """Read the raw 16-bit words.

        Returns:
            tuple: (raw_humidity, raw_temperature); the temperature word is sign + magnitude.
        """
        d = self._read_registers(0x00, 4)
        return ((d[0] << 8) | d[1], (d[2] << 8) | d[3])

    def read_fresh(self):
        """Read twice (at least 2 s apart) and return the second, real-time result.

        Returns:
            dict: {'temperature_c': float, 'humidity_pct': float}
        """
        self.read()
        return self.read()

    def read_registers(self, start, count):
        """Read count registers from start (function 0x03, CRC verified).

        Args:
            start: First register address (0x00–0x1F).
            count: Number of registers, 1–10.

        Returns:
            bytes: The register contents.

        Raises:
            ValueError: If count is not 1–10 or the address range is invalid.
        """
        if not 1 <= count <= 10:
            raise ValueError('count must be 1-10')
        if not 0 <= start <= 0x1F or start + count > 0x20:
            raise ValueError('register range out of bounds')
        return bytes(self._read_registers(start, count))

    def write_registers(self, start, data):
        """Write the user registers 0x10–0x13 (function 0x10 with CRC).

        Args:
            start: First register address (0x10–0x13).
            data: 1–4 bytes; start + len(data) must not pass 0x14.

        Raises:
            ValueError: If the range is not inside the user registers.
            AM2315Error: If the sensor returns an error response.
        """
        data = bytes(data)
        if not 1 <= len(data) <= 4 or start < 0x10 or start + len(data) > 0x14:
            raise ValueError('only the user registers 0x10-0x13 may be written')
        body = bytes([self._FUNC_WRITE, start, len(data)]) + data
        crc = _crc16(body)
        frame = body + bytes([crc & 0xFF, crc >> 8])

        def once():
            resp = self._exchange(frame, 5)
            self._check(resp, self._FUNC_WRITE, 5)
            if resp[1] != start or resp[2] != len(data):
                raise ValueError('AM2315 unexpected write response')
        self._with_retry(once)

    def read_device_info(self):
        """Read the device information (registers 0x08–0x0E).

        Returns:
            dict: {'model': int, 'version': int, 'device_id': int}
        """
        d = self._read_registers(0x08, 7)
        return {'model': (d[0] << 8) | d[1], 'version': d[2],
                'device_id': (d[3] << 24) | (d[4] << 16) | (d[5] << 8) | d[6]}

    def read_user_registers(self):
        """Read the two 16-bit user registers (0x10–0x13).

        Returns:
            tuple: (user1, user2)
        """
        d = self._read_registers(0x10, 4)
        return ((d[0] << 8) | d[1], (d[2] << 8) | d[3])

    def write_user_registers(self, user1, user2):
        """Write the two 16-bit user registers (0x10–0x13).

        Args:
            user1: 16-bit value for registers 0x10–0x11.
            user2: 16-bit value for registers 0x12–0x13.
        """
        self.write_registers(0x10, bytes([(user1 >> 8) & 0xFF, user1 & 0xFF,
                                          (user2 >> 8) & 0xFF, user2 & 0xFF]))

    def wake(self):
        """Wake the sensor: a dummy write (the NACK is ignored) and a 1 ms wait."""
        self._wake()
