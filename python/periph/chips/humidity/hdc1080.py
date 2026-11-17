"""HDC1080 — low-power digital humidity and temperature sensor (Texas Instruments).

14-bit relative humidity and temperature, selectable 14/11/8-bit humidity and
14/11-bit temperature resolution, integrated heater, battery-low flag and
manufacturer/device ID registers (no data-ready pin). 16-bit registers, MSB
first, addressed through a persistent pointer; a measurement is triggered by
a pointer write and read in a separate transaction (the read header is NACKed
until the result is ready). One fixed address 0x40. The same file is used on
MicroPython, CircuitPython and Linux.

Args:
    connection: Plain I2C connection pointing at the device (0x40).
"""

import time

I2C_ADDRESS = 0x40

_REG_TEMPERATURE = 0x00
_REG_HUMIDITY = 0x01
_REG_CONFIG = 0x02
_REG_SERIAL1 = 0xFB
_REG_SERIAL2 = 0xFC
_REG_SERIAL3 = 0xFD
_REG_MANUFACTURER_ID = 0xFE
_REG_DEVICE_ID = 0xFF

_RST = 0x8000
_HEAT = 0x2000
_MODE = 0x1000
_BTST = 0x0800
_TRES = 0x0400
_HRES_MASK = 0x0300
_CONFIG_DEFAULT = 0x1000

_POLL_ATTEMPTS = 50      # x 1 ms = 50 ms timeout


def _to_temperature(raw):
    return raw / 65536.0 * 165.0 - 40.0


def _to_humidity(raw):
    return raw / 65536.0 * 100.0


class HDC1080Minimal:
    """HDC1080 minimal interface: one call reads temperature and humidity.

    init() writes the configuration 0x1000 (14-bit T and RH, sequence mode,
    heater off). The constructor does not touch the bus.

    Args:
        connection: Plain I2C connection (0x40).
    """

    def __init__(self, connection):
        self._connection = connection
        self._config = _CONFIG_DEFAULT

    def init(self):
        """Write the default configuration (0x1000): 14-bit, sequence mode, heater off."""
        self._config = _CONFIG_DEFAULT
        self._write_config()

    def _write_config(self):
        self._connection.write(bytes([_REG_CONFIG, (self._config >> 8) & 0xFF, self._config & 0xFF]))

    def _read_result(self, n):
        for _ in range(_POLL_ATTEMPTS):
            try:
                return bytes(self._connection.read(n))
            except OSError:
                time.sleep(0.001)      # read header NACKed: not ready yet
        raise OSError('HDC1080 measurement did not complete')

    def _trigger_read(self, pointer, n):
        self._connection.write(bytes([pointer]))
        return self._read_result(n)

    def _set_mode(self, sequence):
        want = (self._config | _MODE) if sequence else (self._config & ~_MODE)
        if want != self._config:
            self._config = want
            self._write_config()

    def _measure_pair(self):
        self._set_mode(True)
        d = self._trigger_read(_REG_TEMPERATURE, 4)
        return ((d[0] << 8) | d[1], (d[2] << 8) | d[3])

    def read(self):
        """Measure temperature and humidity (sequence mode, temperature first).

        Returns:
            tuple: (temperature_c, humidity_pct).

        Raises:
            OSError: If the measurement does not complete within 50 ms.
        """
        t, h = self._measure_pair()
        return (_to_temperature(t), _to_humidity(h))


class HDC1080Full(HDC1080Minimal):
    """HDC1080 full interface: single values, resolution, heater, battery, serial ID, IDs.

    Args:
        connection: Plain I2C connection (0x40).
    """

    def read_raw(self):
        """Measure and return the raw 16-bit words.

        Returns:
            tuple: (raw_temperature, raw_humidity), bits 1:0 zero.
        """
        return self._measure_pair()

    def read_temperature(self):
        """Measure temperature only (single mode).

        Returns:
            float: Degrees Celsius.
        """
        self._set_mode(False)
        d = self._trigger_read(_REG_TEMPERATURE, 2)
        return _to_temperature((d[0] << 8) | d[1])

    def read_humidity(self):
        """Measure humidity only (single mode).

        Returns:
            float: Relative humidity in %RH.
        """
        self._set_mode(False)
        d = self._trigger_read(_REG_HUMIDITY, 2)
        return _to_humidity((d[0] << 8) | d[1])

    def set_resolution(self, temperature_bits, humidity_bits):
        """Select the conversion resolutions.

        Args:
            temperature_bits: 14 or 11.
            humidity_bits: 14, 11 or 8.

        Raises:
            ValueError: For any other value.
        """
        if temperature_bits not in (14, 11):
            raise ValueError('temperature_bits must be 14 or 11')
        if humidity_bits not in (14, 11, 8):
            raise ValueError('humidity_bits must be 14, 11 or 8')
        cfg = self._config & ~(_TRES | _HRES_MASK)
        if temperature_bits == 11:
            cfg |= _TRES
        cfg |= {14: 0x0000, 11: 0x0100, 8: 0x0200}[humidity_bits]
        self._config = cfg
        self._write_config()

    def set_heater(self, on):
        """Enable or disable the heater (read-modify-write of the stored configuration).

        Args:
            on: True to enable.
        """
        self._config = (self._config | _HEAT) if on else (self._config & ~_HEAT)
        self._write_config()

    def is_heater_on(self):
        """Return True if the heater bit is set in the configuration register."""
        return bool(self.read_config() & _HEAT)

    def battery_low(self):
        """Return True if VDD is below 2.8 V (BTST)."""
        return bool(self.read_config() & _BTST)

    def read_config(self):
        """Read the configuration register.

        Returns:
            int: 16-bit configuration word.
        """
        self._connection.write(bytes([_REG_CONFIG]))
        d = bytes(self._connection.read(2))
        return (d[0] << 8) | d[1]

    def software_reset(self):
        """Set RST, wait 15 ms, then rewrite the stored configuration."""
        self._connection.write(bytes([_REG_CONFIG, (_RST >> 8) & 0xFF, 0x00]))
        time.sleep(0.015)
        self._write_config()

    def serial_id(self):
        """Read the 40-bit serial number from registers 0xFB-0xFD.

        Returns:
            int: Serial ID.
        """
        words = []
        for reg in (_REG_SERIAL1, _REG_SERIAL2, _REG_SERIAL3):
            self._connection.write(bytes([reg]))
            d = bytes(self._connection.read(2))
            words.append((d[0] << 8) | d[1])
        return (words[0] << 24) | (words[1] << 8) | (words[2] >> 7)

    def conversion_time(self):
        """Typical conversion time for the current resolutions.

        Returns:
            float: Seconds (temperature + humidity in sequence mode).
        """
        t = 0.00365 if self._config & _TRES else 0.00635
        h = {0x0000: 0.0065, 0x0100: 0.00385, 0x0200: 0.0025}.get(self._config & _HRES_MASK, 0.0065)
        return t + h

    def _read_word(self, reg):
        self._connection.write(bytes([reg]))
        d = bytes(self._connection.read(2))
        return (d[0] << 8) | d[1]

    def manufacturer_id(self):
        """Read the manufacturer ID register (0xFE).

        Returns:
            int: 0x5449 for Texas Instruments.
        """
        return self._read_word(_REG_MANUFACTURER_ID)

    def device_id(self):
        """Read the device ID register (0xFF).

        Returns:
            int: 0x1050 for the HDC1080.
        """
        return self._read_word(_REG_DEVICE_ID)
