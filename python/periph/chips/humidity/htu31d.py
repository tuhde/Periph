import math
import time


def _crc8(data):
    """CRC-8 used by the HTU31D: polynomial 0x31 (x^8 + x^5 + x^4 + 1), init 0x00, no final XOR."""
    crc = 0x00
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 0x80:
                crc = ((crc << 1) ^ 0x31) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
    return crc


class HTU31DMinimal:
    """HTU31D humidity and temperature sensor — minimal interface.

    Each read() starts one conversion at the highest resolution (command 0x5E,
    OSR 3 for humidity and temperature), waits 25 ms, reads the temperature and
    humidity words (command 0x00) and verifies both CRC-8 bytes. Humidity is
    clamped to 0-100 %RH.

    The device is command driven (no register pointer), so a plain I²C
    connection is used. The constructor does not touch the bus; init() performs
    the soft reset.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x40 or 0x41).
    """

    _CMD_RESET = 0x1E
    _CMD_HEATER_ON = 0x04
    _CMD_HEATER_OFF = 0x02
    _CMD_CONVERT = 0x40
    _CMD_READ_TRH = 0x00
    _CMD_READ_RH = 0x10
    _CMD_READ_DIAG = 0x08
    _CMD_READ_SERIAL = 0x0A
    # typical conversion times in seconds, indexed by OSR 0..3
    _T_RH = (0.00111, 0.00214, 0.00421, 0.00834)
    _T_T = (0.00157, 0.00306, 0.00603, 0.01198)

    def __init__(self, connection):
        self._connection = connection
        self._osr_rh = 3
        self._osr_t = 3

    def init(self):
        """Soft reset (0x1E) and wait 15 ms; heater off, highest resolution."""
        self._connection.write(bytes([self._CMD_RESET]))
        time.sleep(0.015)

    def _convert_command(self):
        return self._CMD_CONVERT | (self._osr_rh << 3) | (self._osr_t << 1)

    def _wait_time(self):
        if self._osr_rh == 3 and self._osr_t == 3:
            return 0.025
        return 1.25 * (self._T_RH[self._osr_rh] + self._T_T[self._osr_t])

    def _start(self):
        self._connection.write(bytes([self._convert_command()]))

    def _read_words(self, command, count):
        """Write a read command and return the CRC-verified 16-bit words."""
        self._connection.write(bytes([command]))
        data = bytes(self._connection.read(3 * count))
        words = []
        for i in range(count):
            chunk = data[3 * i:3 * i + 3]
            if _crc8(chunk[0:2]) != chunk[2]:
                raise ValueError('HTU31D CRC mismatch')
            words.append((chunk[0] << 8) | chunk[1])
        return words

    @staticmethod
    def _to_humidity(code):
        return min(100.0, max(0.0, 100.0 * code / 65535.0))

    @staticmethod
    def _to_temperature(code):
        return -40.0 + 165.0 * code / 65535.0

    def _measure(self, command, count):
        self._start()
        time.sleep(self._wait_time())
        return self._read_words(command, count)

    def read(self):
        """Measure temperature and humidity.

        Returns:
            dict: {'temperature_c': float °C, 'humidity_pct': float %RH (0-100)}.

        Raises:
            ValueError: If a result fails its CRC check.
        """
        t, rh = self._measure(self._CMD_READ_TRH, 2)
        return {'temperature_c': self._to_temperature(t), 'humidity_pct': self._to_humidity(rh)}


class HTU31DFull(HTU31DMinimal):
    """HTU31D full interface — oversampling, non-blocking conversion, heater, diagnostics, dew point.

    Extends HTU31DMinimal with single values, raw codes, separate oversampling
    settings for humidity and temperature, the on-chip heater, the diagnostic
    byte, the serial number, soft reset and the dew point.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x40 or 0x41).
    """

    _DIAG_FLAGS = (
        (7, 'nvm_err'), (6, 'rh_overrun'), (5, 'rh_high_err'), (4, 'rh_low_err'),
        (3, 't_overrun'), (2, 't_high_err'), (1, 't_low_err'), (0, 'heater_on'),
    )

    def __init__(self, connection):
        super().__init__(connection)

    def read_humidity(self):
        """Run one humidity measurement (command 0x10 after the conversion).

        Returns:
            float: Relative humidity in %RH, clamped to 0-100.
        """
        return self._to_humidity(self._measure(self._CMD_READ_RH, 1)[0])

    def read_temperature(self):
        """Run one temperature measurement (the read stops after the temperature word).

        Returns:
            float: Temperature in degrees Celsius.
        """
        return self._to_temperature(self._measure(self._CMD_READ_TRH, 1)[0])

    def read_raw(self):
        """Measure temperature and humidity and return the raw codes.

        Returns:
            tuple: (t_raw, rh_raw), both 16-bit unsigned.
        """
        t, rh = self._measure(self._CMD_READ_TRH, 2)
        return (t, rh)

    def set_resolution(self, osr_rh, osr_t):
        """Select the oversampling of the following measurements.

        Args:
            osr_rh: Humidity oversampling 0-3 (0.020 ... 0.007 %RH).
            osr_t: Temperature oversampling 0-3 (0.040 ... 0.012 °C).

        Raises:
            ValueError: If either value is not 0-3.
        """
        if osr_rh not in (0, 1, 2, 3) or osr_t not in (0, 1, 2, 3):
            raise ValueError('osr must be 0-3')
        self._osr_rh = osr_rh
        self._osr_t = osr_t

    def get_resolution(self):
        """Return the oversampling setting.

        Returns:
            tuple: (osr_rh, osr_t).
        """
        return (self._osr_rh, self._osr_t)

    def conversion_time(self):
        """Typical conversion time of one measurement at the current oversampling.

        Returns:
            float: Seconds (sum of the humidity and temperature times).
        """
        return self._T_RH[self._osr_rh] + self._T_T[self._osr_t]

    def start_conversion(self):
        """Write the conversion command only (non-blocking use).

        Wait conversion_time() (plus margin) before calling read_result().
        """
        self._start()

    def read_result(self):
        """Read the result of the last conversion (command 0x00).

        Returns:
            dict: {'temperature_c': float °C, 'humidity_pct': float %RH}.

        Raises:
            ValueError: If a result fails its CRC check.
        """
        t, rh = self._read_words(self._CMD_READ_TRH, 2)
        return {'temperature_c': self._to_temperature(t), 'humidity_pct': self._to_humidity(rh)}

    def set_heater(self, on):
        """Switch the on-chip heater on (0x04) or off (0x02).

        Args:
            on: True for on, False for off.
        """
        self._connection.write(bytes([self._CMD_HEATER_ON if on else self._CMD_HEATER_OFF]))

    def diagnostic(self):
        """Read the diagnostic byte (command 0x08).

        Returns:
            int: Raw diagnostic byte.
        """
        self._connection.write(bytes([self._CMD_READ_DIAG]))
        return bytes(self._connection.read(1))[0]

    def diagnostic_flags(self):
        """Read the diagnostic byte and name its bits.

        Returns:
            dict: nvm_err, rh_overrun, rh_high_err, rh_low_err, t_overrun,
            t_high_err, t_low_err, heater_on — each a bool.
        """
        value = self.diagnostic()
        return {name: bool((value >> bit) & 1) for bit, name in self._DIAG_FLAGS}

    def is_heater_on(self):
        """Return True if the diagnostic byte reports the heater on."""
        return bool(self.diagnostic() & 0x01)

    def serial_number(self):
        """Read the 24-bit serial number (command 0x0A, CRC verified).

        Returns:
            int: 24-bit serial number.

        Raises:
            ValueError: If the CRC does not match.
        """
        self._connection.write(bytes([self._CMD_READ_SERIAL]))
        data = bytes(self._connection.read(4))
        if _crc8(data[0:3]) != data[3]:
            raise ValueError('HTU31D CRC mismatch')
        return (data[0] << 16) | (data[1] << 8) | data[2]

    def software_reset(self):
        """Soft reset (0x1E) and wait 15 ms; heater off, oversampling back to OSR 3 / 3."""
        self._connection.write(bytes([self._CMD_RESET]))
        time.sleep(0.015)
        self._osr_rh = 3
        self._osr_t = 3

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
