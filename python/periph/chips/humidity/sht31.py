import math
import time


def _crc8(data):
    """CRC-8 used by the SHT3x: polynomial 0x31, init 0xFF, no reflection, no final XOR."""
    crc = 0xFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 0x80:
                crc = ((crc << 1) ^ 0x31) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
    return crc


class SHT31Minimal:
    """SHT31 temperature and humidity sensor — minimal interface.

    Each read() runs one single-shot measurement at high repeatability with
    clock stretching disabled and CRC checking on. The heater stays off.

    Default configuration (baked in at construction):
        - Soft reset (0x30A2) with 1 ms waits before and after
        - Single shot, high repeatability (0x2400), fixed 15 ms wait; if the
          read header is still NACKed the driver keeps polling for up to
          20 ms more
        - Both CRC-8 bytes verified; one retry of the whole measurement on a
          mismatch, then ValueError

    Args:
        connection: Configured I²C connection pointing at the device
            (address 0x44 with ADDR low, 0x45 with ADDR high).
    """

    _CMD_SOFT_RESET = 0x30A2
    _CMD_HEATER_ON = 0x306D
    _CMD_HEATER_OFF = 0x3066
    _CMD_READ_STATUS = 0xF32D
    _CMD_CLEAR_STATUS = 0x3041
    _CMD_FETCH = 0xE000
    _CMD_BREAK = 0x3093
    _CMD_ART = 0x2B32
    # single shot: (stretching disabled, stretching enabled, wait in seconds)
    _SINGLE = {
        'high': (0x2400, 0x2C06, 0.015),
        'medium': (0x240B, 0x2C0D, 0.006),
        'low': (0x2416, 0x2C10, 0.004),
    }

    def __init__(self, connection):
        self._connection = connection
        self._repeatability = 'high'
        self._stretch = False
        time.sleep(0.001)
        self._command(self._CMD_SOFT_RESET)
        time.sleep(0.001)

    def _command(self, code):
        self._connection.write(bytes([code >> 8, code & 0xFF]))

    @staticmethod
    def _decode(data):
        """Verify both CRCs of a 6-byte result and return the raw (temperature, humidity) words."""
        if _crc8(data[0:2]) != data[2] or _crc8(data[3:5]) != data[5]:
            raise ValueError('SHT31 CRC mismatch')
        return ((data[0] << 8) | data[1], (data[3] << 8) | data[4])

    @staticmethod
    def _convert(raw_t, raw_rh):
        return (-45.0 + 175.0 * raw_t / 65535.0, 100.0 * raw_rh / 65535.0)

    def _single_shot(self):
        """One single-shot measurement; returns the raw (temperature, humidity) words."""
        no_stretch, stretch, wait = self._SINGLE[self._repeatability]
        for attempt in range(2):
            self._command(stretch if self._stretch else no_stretch)
            if not self._stretch:
                time.sleep(wait)
            data = None
            for _ in range(21):
                try:
                    data = bytes(self._connection.read(6))
                    break
                except OSError:
                    time.sleep(0.001)      # read header NACKed: not ready yet
            if data is None:
                raise OSError('SHT31 measurement did not complete')
            try:
                return self._decode(data)
            except ValueError:
                if attempt == 1:
                    raise

    def read(self):
        """Run one single-shot measurement.

        Returns:
            tuple: (temperature_c, humidity_pct) — degrees Celsius and %RH.

        Raises:
            ValueError: If a CRC mismatch persists after one retry.
        """
        raw_t, raw_rh = self._single_shot()
        return self._convert(raw_t, raw_rh)


class SHT31Full(SHT31Minimal):
    """SHT31 full interface — repeatability, clock stretching, periodic mode, heater and status.

    Extends SHT31Minimal with the remaining chip features: single values and raw
    words, the three repeatability levels, the clock-stretching command set,
    periodic measurements (0.5–10 mps plus ART), the heater, the status register,
    soft reset and the dew point.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x44 or 0x45).
    """

    # periodic: rate -> {repeatability: command}
    _PERIODIC = {
        0.5: {'high': 0x2032, 'medium': 0x2024, 'low': 0x202F},
        1: {'high': 0x2130, 'medium': 0x2126, 'low': 0x212D},
        2: {'high': 0x2236, 'medium': 0x2220, 'low': 0x222B},
        4: {'high': 0x2334, 'medium': 0x2322, 'low': 0x2329},
        10: {'high': 0x2737, 'medium': 0x2721, 'low': 0x272A},
    }

    def __init__(self, connection):
        super().__init__(connection)

    def read_temperature(self):
        """Run one measurement and return the temperature only.

        Returns:
            float: Temperature in degrees Celsius.
        """
        return self.read()[0]

    def read_humidity(self):
        """Run one measurement and return the relative humidity only.

        Returns:
            float: Relative humidity in %RH.
        """
        return self.read()[1]

    def read_raw(self):
        """Run one measurement and return the raw 16-bit words.

        Returns:
            tuple: (raw_temperature, raw_humidity), each 0–65535.
        """
        return self._single_shot()

    def set_repeatability(self, level):
        """Select the repeatability for later single-shot reads.

        Args:
            level: 'low', 'medium' or 'high' (measurement time 4 / 6 / 15 ms).

        Raises:
            ValueError: If level is not one of the three names.
        """
        if level not in self._SINGLE:
            raise ValueError("level must be 'low', 'medium' or 'high'")
        self._repeatability = level

    def set_clock_stretching(self, on):
        """Use the clock-stretching command set for single-shot reads.

        The bus layer must support clock stretching; the fixed wait is skipped.

        Args:
            on: True to enable, False for the polling (NACK) variant.
        """
        self._stretch = bool(on)

    def start_periodic(self, rate, repeatability='high', art=False):
        """Start periodic measurements; read results with fetch().

        Args:
            rate: Measurements per second: 0.5, 1, 2, 4 or 10 (ignored with art).
            repeatability: 'low', 'medium' or 'high'.
            art: True for the accelerated response time mode (4 mps, command 0x2B32).

        Raises:
            ValueError: For an unsupported rate or repeatability.
        """
        if art:
            self._command(self._CMD_ART)
            return
        if rate not in self._PERIODIC:
            raise ValueError('rate must be 0.5, 1, 2, 4 or 10')
        if repeatability not in self._SINGLE:
            raise ValueError("repeatability must be 'low', 'medium' or 'high'")
        self._command(self._PERIODIC[rate][repeatability])

    def fetch(self):
        """Read the latest periodic result (command 0xE000).

        Returns:
            tuple: (temperature_c, humidity_pct), or None if no new data is available
                (the read header is NACKed).

        Raises:
            ValueError: If a CRC check fails.
        """
        self._command(self._CMD_FETCH)
        try:
            data = bytes(self._connection.read(6))
        except OSError:
            return None
        raw_t, raw_rh = self._decode(data)
        return self._convert(raw_t, raw_rh)

    def stop_periodic(self):
        """Stop periodic mode (break command 0x3093) and wait 1 ms."""
        self._command(self._CMD_BREAK)
        time.sleep(0.001)

    def set_heater(self, on):
        """Switch the on-chip heater (about 5–25 mW) on or off.

        Args:
            on: True for on (0x306D), False for off (0x3066).
        """
        self._command(self._CMD_HEATER_ON if on else self._CMD_HEATER_OFF)

    def read_status(self):
        """Read the status register (command 0xF32D, CRC verified).

        Returns:
            int: 16-bit status, e.g. 0x8010 after reset; bit 13 = heater on.

        Raises:
            ValueError: If the CRC check fails.
        """
        self._command(self._CMD_READ_STATUS)
        data = bytes(self._connection.read(3))
        if _crc8(data[0:2]) != data[2]:
            raise ValueError('SHT31 CRC mismatch')
        return (data[0] << 8) | data[1]

    def clear_status(self):
        """Clear the status register flags (command 0x3041)."""
        self._command(self._CMD_CLEAR_STATUS)

    def soft_reset(self):
        """Soft reset (0x30A2), wait 1 ms; leaves periodic mode and returns to single shot."""
        self._command(self._CMD_SOFT_RESET)
        time.sleep(0.001)

    def dew_point(self):
        """Run one measurement and compute the dew point (Magnus formula).

        Returns:
            float: Dew point in degrees Celsius.
        """
        t, rh = self.read()
        if rh <= 0.0:
            rh = 0.01
        gamma = math.log(rh / 100.0) + (17.62 * t) / (243.12 + t)
        return 243.12 * gamma / (17.62 - gamma)
