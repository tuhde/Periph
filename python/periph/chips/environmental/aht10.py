import math
import time


class AHT10Minimal:
    """AHT10 temperature and humidity sensor — minimal interface.

    Provides temperature and humidity readings with no configuration beyond
    the connection. Handles power-on wait, initialization (calibration enable),
    calibration check and measurement triggering automatically.

    Default configuration (baked in at construction):
        - One-shot measurement triggered on every read() call
        - Fixed 80 ms wait after trigger, then BUSY polling (10 ms steps, 200 ms guard)
        - No CRC (the AHT10 does not send one)

    Args:
        connection: Configured I²C connection pointing at the device (address 0x38).

    Raises:
        RuntimeError: If the sensor does not report calibrated after init and one retry.
    """

    _CMD_INIT = b'\xE1\x08\x00'
    _CMD_TRIGGER = b'\xAC\x33\x00'
    _CMD_SOFT_RESET = b'\xBA'

    _STATUS_BUSY = 0x80
    _STATUS_CAL = 0x08

    def __init__(self, connection):
        self._connection = connection
        time.sleep(0.04)
        self._initialize()
        if not self._read_status() & self._STATUS_CAL:
            self._connection.write(self._CMD_SOFT_RESET)
            time.sleep(0.02)
            self._initialize()
            if not self._read_status() & self._STATUS_CAL:
                raise RuntimeError('AHT10 not calibrated after init')

    def _initialize(self):
        self._connection.write(self._CMD_INIT)
        time.sleep(0.01)

    def _read_status(self):
        return self._connection.read(1)[0]

    def _measure(self):
        # Trigger takes > 75 ms; poll BUSY afterwards in case it is still converting.
        self._connection.write(self._CMD_TRIGGER)
        time.sleep(0.08)
        waited = 0
        while True:
            data = self._connection.read(6)
            if not data[0] & self._STATUS_BUSY:
                return data
            if waited >= 200:
                raise RuntimeError('AHT10 measurement timeout')
            time.sleep(0.01)
            waited += 10

    @staticmethod
    def _raw(data):
        raw_rh = (data[1] << 12) | (data[2] << 4) | (data[3] >> 4)
        raw_t = ((data[3] & 0x0F) << 16) | (data[4] << 8) | data[5]
        return raw_rh, raw_t

    @staticmethod
    def _convert(raw_rh, raw_t):
        humidity_pct = min(max(raw_rh / 1048576.0 * 100.0, 0.0), 100.0)
        temperature_c = raw_t / 1048576.0 * 200.0 - 50.0
        return temperature_c, humidity_pct

    def read(self):
        """Trigger a measurement and return temperature and humidity.

        Sends the trigger command, waits 80 ms, polls BUSY, reads 6 bytes and
        decodes the raw 20-bit values into physical units.

        Returns:
            dict: {'temperature_c': float, 'humidity_pct': float}
                temperature_c: Temperature in degrees Celsius (-50 to 150 °C).
                humidity_pct: Relative humidity in percent (0 to 100 %RH).

        Raises:
            RuntimeError: If BUSY does not clear within 200 ms after the 80 ms wait.
        """
        raw_rh, raw_t = self._raw(self._measure())
        temperature_c, humidity_pct = self._convert(raw_rh, raw_t)
        return {'temperature_c': temperature_c, 'humidity_pct': humidity_pct}


class AHT10Full(AHT10Minimal):
    """AHT10 full interface — extends AHT10Minimal with raw values, status and dew point.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x38).
    """

    def __init__(self, connection):
        super().__init__(connection)

    def read_temperature(self):
        """Trigger a measurement and return temperature only.

        Returns:
            float: Temperature in degrees Celsius (-50 to 150 °C).
        """
        return self.read()['temperature_c']

    def read_humidity(self):
        """Trigger a measurement and return humidity only.

        Returns:
            float: Relative humidity in percent (0 to 100 %RH).
        """
        return self.read()['humidity_pct']

    def read_raw(self):
        """Trigger a measurement and return the raw 20-bit values.

        Returns:
            tuple: (raw_humidity, raw_temperature), each 0 to 1048575.
        """
        return self._raw(self._measure())

    def read_status(self):
        """Read the status byte.

        Returns:
            int: Status byte (bit 7 BUSY, bits 6:5 MODE, bit 3 CAL).
        """
        return self._read_status()

    def is_calibrated(self):
        """Check if the calibration bit is set in the status byte.

        Returns:
            bool: True if the sensor reports calibration enabled.
        """
        return bool(self._read_status() & self._STATUS_CAL)

    def is_busy(self):
        """Check if the busy bit is set in the status byte.

        Returns:
            bool: True if a measurement is in progress.
        """
        return bool(self._read_status() & self._STATUS_BUSY)

    def soft_reset(self):
        """Send the soft reset command, wait 20 ms, and re-run the init command."""
        self._connection.write(self._CMD_SOFT_RESET)
        time.sleep(0.02)
        self._initialize()

    def dew_point(self):
        """Trigger a measurement and return the dew point (Magnus formula).

        Returns:
            float: Dew point in degrees Celsius. Returns -50.0 if humidity is 0 %RH.
        """
        m = self.read()
        rh = m['humidity_pct']
        if rh <= 0.0:
            return -50.0
        t = m['temperature_c']
        gamma = math.log(rh / 100.0) + 17.62 * t / (243.12 + t)
        return 243.12 * gamma / (17.62 - gamma)
