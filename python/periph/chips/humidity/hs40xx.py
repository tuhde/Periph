import math
import time


def _crc8(data):
    """CRC-8 (poly 0x1D, init 0xFF, no reflection, xor-out 0) over ``data``."""
    crc = 0xFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1D) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


class HS40xxMinimal:
    """HS40xx humidity and temperature sensor — minimal interface.

    Covers the Renesas HS4001 and HS4003, which share one protocol (HS4002 and
    the HS401x variants differ only physically and get no separate class). Each
    read() sends the no-hold humidity + temperature command (0xF5), waits 3 ms,
    reads five bytes (retrying while the sensor NACKs the read header), verifies
    the CRC-8 and converts. A CRC mismatch is retried once.

    The constructor performs no bus access.

    Default configuration (power-on): 14-bit resolution, one-shot no-hold
    measurements, CRC checked, periodic mode and alerts off.

    Args:
        connection: Configured I²C connection pointing at the device (address 0x54).
    """

    _CMD_T_HOLD = 0xE3
    _CMD_T_NOHOLD = 0xF3
    _CMD_HT_HOLD = 0xE5
    _CMD_HT_NOHOLD = 0xF5

    _FULL_SCALE = 16383.0

    def __init__(self, connection):
        self._connection = connection

    def _read_frame(self, length, timeout_s=0.02):
        # The sensor NACKs the read header while converting; retry until it answers.
        deadline = time.time() + timeout_s
        while True:
            try:
                return bytes(self._connection.read(length))
            except OSError:
                if time.time() >= deadline:
                    raise
                time.sleep(0.001)

    @staticmethod
    def _check_crc(frame):
        # Temperature-only frames (3 bytes) take the humidity bytes as zero.
        data = bytes(2) + frame[:2] if len(frame) == 3 else frame[:4]
        return _crc8(data) == frame[len(frame) - 1]

    def _measure(self, cmd, length, hold=False):
        for attempt in range(2):
            self._connection.write(bytes([cmd]))
            if not hold:
                time.sleep(0.003)
            frame = self._read_frame(length)
            if self._check_crc(frame):
                return frame
        raise RuntimeError('HS40xx CRC mismatch')

    @staticmethod
    def _raw_humidity(frame):
        return ((frame[0] & 0x3F) << 8) | frame[1]

    @staticmethod
    def _raw_temperature(frame, offset=2):
        return ((frame[offset] & 0x3F) << 8) | frame[offset + 1]

    @classmethod
    def _to_humidity(cls, raw):
        return raw / cls._FULL_SCALE * 100.0

    @classmethod
    def _to_temperature(cls, raw):
        return raw / cls._FULL_SCALE * 165.0 - 40.0

    def read(self):
        """Measure temperature and humidity (no-hold 0xF5, 5 bytes, CRC verified).

        Returns:
            dict: {'temperature_c': float, 'humidity_pct': float}
                temperature_c: Temperature in degrees Celsius (-40 to 125 °C).
                humidity_pct: Relative humidity in percent (0 to 100 %RH).

        Raises:
            RuntimeError: If the CRC is wrong after one retry.
            OSError: If the sensor keeps NACKing the read for more than 20 ms.
        """
        frame = self._measure(self._CMD_HT_NOHOLD, 5)
        return {'temperature_c': self._to_temperature(self._raw_temperature(frame)),
                'humidity_pct': self._to_humidity(self._raw_humidity(frame))}


class HS40xxFull(HS40xxMinimal):
    """HS40xx full interface — single values, hold mode, resolution, periodic mode, alerts, ID.

    Extends HS40xxMinimal. Registers are volatile: they return to their defaults
    whenever the supply is cut or after soft_reset().

    Args:
        connection: Configured I²C connection pointing at the device (address 0x54).
    """

    SOURCE_RH_LOW = 0x01
    SOURCE_RH_HIGH = 0x02
    SOURCE_TEMP_LOW = 0x04
    SOURCE_TEMP_HIGH = 0x08

    _CMD_READ_REG = 0xA7
    _CMD_WRITE_REG = 0xA6
    _CMD_STOP_PERIODIC = 0x30
    _CMD_READ_ID = 0xD7
    _CMD_SOFT_RESET = 0xFE

    _REG_RESOLUTION = 0x00
    _REG_PERIODIC = 0x02
    _REG_ALERT_EN = 0x03

    # (low-byte register, high-byte register) of each 14-bit threshold
    _THRESHOLDS = {
        'rh_high': (0x0F, 0x10), 'rh_high_reset': (0x0D, 0x0E),
        'rh_low': (0x11, 0x12), 'rh_low_reset': (0x13, 0x14),
        'temp_high': (0x07, 0x08), 'temp_high_reset': (0x05, 0x06),
        'temp_low': (0x09, 0x0A), 'temp_low_reset': (0x0B, 0x0C),
    }

    _BITS = (8, 10, 12, 14)
    _PERIODS = {0.5: 0, 1: 1, 1.0: 1, 2.5: 2}

    def __init__(self, connection):
        super().__init__(connection)
        self._alerts_enabled = False
        self._period_s = 1.0
        self._active_high = True
        self._int_callback = None
        self._int_handler = None
        self._int_pin = None
        self._poll_stop = None

    def read_temperature(self):
        """Measure temperature only (no-hold 0xF3, 3 bytes, CRC verified).

        Returns:
            float: Temperature in degrees Celsius.

        Raises:
            RuntimeError: If the CRC is wrong after one retry.
        """
        frame = self._measure(self._CMD_T_NOHOLD, 3)
        return self._to_temperature(self._raw_temperature(frame, 0))

    def read_humidity(self):
        """Measure humidity (uses the 0xF5 humidity + temperature command).

        Returns:
            float: Relative humidity in percent (0 to 100 %RH).

        Raises:
            RuntimeError: If the CRC is wrong after one retry.
        """
        return self.read()['humidity_pct']

    def read_hold(self):
        """Measure with the hold command 0xE5 (the sensor stretches SCL until done).

        Needs a clock-stretching-capable controller and SCL >= 200 kHz.

        Returns:
            dict: {'temperature_c': float, 'humidity_pct': float}

        Raises:
            RuntimeError: If the CRC is wrong after one retry.
        """
        frame = self._measure(self._CMD_HT_HOLD, 5, hold=True)
        return {'temperature_c': self._to_temperature(self._raw_temperature(frame)),
                'humidity_pct': self._to_humidity(self._raw_humidity(frame))}

    def read_raw(self):
        """Measure and return the raw 14-bit values (CRC verified).

        Returns:
            tuple: (raw_humidity, raw_temperature), each 0 to 16383.
        """
        frame = self._measure(self._CMD_HT_NOHOLD, 5)
        return (self._raw_humidity(frame), self._raw_temperature(frame))

    def read_register(self, reg):
        """Read one configuration register (command 0xA7 + address, then a 1-byte read).

        Args:
            reg: Register address (0x00 to 0x14).

        Returns:
            int: Register value (0 to 255).
        """
        self._connection.write(bytes([self._CMD_READ_REG, reg]))
        return self._read_frame(1)[0]

    def write_register(self, reg, value):
        """Write one configuration register (command 0xA6 + address + data).

        Args:
            reg: Register address (0x00 to 0x14).
            value: Byte to write (0 to 255).
        """
        self._connection.write(bytes([self._CMD_WRITE_REG, reg, value & 0xFF]))

    def _modify(self, reg, mask, bits):
        value = (self.read_register(reg) & ~mask & 0xFF) | (bits & mask)
        self.write_register(reg, value)

    def set_resolution(self, humidity_bits=None, temperature_bits=None):
        """Set the measurement resolutions (register 0x00, read-modify-write).

        Args:
            humidity_bits: 8, 10, 12 or 14, or None to leave unchanged.
            temperature_bits: 8, 10, 12 or 14, or None to leave unchanged.

        Raises:
            ValueError: If a value is not 8, 10, 12 or 14.
        """
        for bits in (humidity_bits, temperature_bits):
            if bits is not None and bits not in self._BITS:
                raise ValueError('resolution must be 8, 10, 12 or 14 bits')
        mask = 0
        value = 0
        if humidity_bits is not None:
            mask |= 0x0C
            value |= self._BITS.index(humidity_bits) << 2
        if temperature_bits is not None:
            mask |= 0x03
            value |= self._BITS.index(temperature_bits)
        if mask:
            self._modify(self._REG_RESOLUTION, mask, value)

    def get_resolution(self):
        """Read the measurement resolutions.

        Returns:
            tuple: (humidity_bits, temperature_bits), each 8, 10, 12 or 14.
        """
        reg = self.read_register(self._REG_RESOLUTION)
        return (self._BITS[(reg >> 2) & 3], self._BITS[reg & 3])

    def start_periodic(self, period_s=1.0):
        """Start periodic measurement mode (register 0x02 bit 7 plus the period).

        Args:
            period_s: Measurement period in seconds: 0.5, 1 or 2.5.

        Raises:
            ValueError: If the period is not supported.
        """
        if period_s not in self._PERIODS:
            raise ValueError('period_s must be 0.5, 1 or 2.5')
        self._alerts_enabled = (self.read_register(self._REG_ALERT_EN) & 0x0F) != 0
        self._period_s = float(period_s)
        self._modify(self._REG_PERIODIC, 0xB0, 0x80 | (self._PERIODS[period_s] << 4))

    def stop_periodic(self):
        """Leave periodic mode (command 0x30); the sensor then sleeps."""
        self._connection.write(bytes([self._CMD_STOP_PERIODIC]))

    def read_periodic(self):
        """Fetch the latest periodic result (a plain read; CRC verified).

        The frame has a leading status byte when any alert is enabled.

        Returns:
            dict: {'temperature_c', 'humidity_pct', 'status'} — status is the
                SOURCE_* bitmask of the active alerts (0 when alerts are off).

        Raises:
            RuntimeError: If the CRC does not match.
        """
        status = 0
        length = 6 if self._alerts_enabled else 5
        frame = self._read_frame(length)
        if self._alerts_enabled:
            status = frame[0] & 0x0F
            frame = frame[1:]
        if not self._check_crc(frame):
            raise RuntimeError('HS40xx CRC mismatch')
        return {'temperature_c': self._to_temperature(self._raw_temperature(frame)),
                'humidity_pct': self._to_humidity(self._raw_humidity(frame)),
                'status': status}

    @staticmethod
    def _rh_word(rh):
        return max(0, min(16383, int(round(rh / 100.0 * 16383))))

    @staticmethod
    def _temp_word(t):
        return max(0, min(16383, int(round((t + 40.0) / 165.0 * 16383))))

    def set_alert_thresholds(self, rh_high=None, rh_high_reset=None, rh_low=None, rh_low_reset=None,
                             temp_high=None, temp_high_reset=None, temp_low=None, temp_low_reset=None):
        """Program alert trigger / reset thresholds (only the given ones are written).

        Args:
            rh_high, rh_high_reset, rh_low, rh_low_reset: Humidity thresholds in %RH.
            temp_high, temp_high_reset, temp_low, temp_low_reset: Temperature thresholds in °C.
        """
        values = {'rh_high': rh_high, 'rh_high_reset': rh_high_reset, 'rh_low': rh_low,
                  'rh_low_reset': rh_low_reset, 'temp_high': temp_high,
                  'temp_high_reset': temp_high_reset, 'temp_low': temp_low,
                  'temp_low_reset': temp_low_reset}
        for name in ('rh_high', 'rh_high_reset', 'rh_low', 'rh_low_reset',
                     'temp_high', 'temp_high_reset', 'temp_low', 'temp_low_reset'):
            if values[name] is None:
                continue
            word = self._rh_word(values[name]) if name.startswith('rh') else self._temp_word(values[name])
            low_reg, high_reg = self._THRESHOLDS[name]
            self.write_register(low_reg, word & 0xFF)
            self.write_register(high_reg, word >> 8)

    def set_alert_polarity(self, active_high):
        """Set the ALERT pin polarity (register 0x02 bit 0).

        Args:
            active_high: True for active high when triggered (default), False for active low.
        """
        self._active_high = bool(active_high)
        self._modify(self._REG_PERIODIC, 0x01, 1 if active_high else 0)

    def enable_interrupt(self, source):
        """Enable alert source(s) in register 0x03.

        Args:
            source: Bitmask of SOURCE_RH_LOW, SOURCE_RH_HIGH, SOURCE_TEMP_LOW, SOURCE_TEMP_HIGH.
        """
        value = self.read_register(self._REG_ALERT_EN) | (source & 0x0F)
        self.write_register(self._REG_ALERT_EN, value)
        self._alerts_enabled = (value & 0x0F) != 0

    def disable_interrupt(self, source):
        """Disable alert source(s) in register 0x03.

        Args:
            source: Bitmask of SOURCE_* constants.
        """
        value = self.read_register(self._REG_ALERT_EN) & ~(source & 0x0F) & 0xFF
        self.write_register(self._REG_ALERT_EN, value)
        self._alerts_enabled = (value & 0x0F) != 0

    def poll_interrupt(self):
        """Fetch the latest periodic result and return the alert status.

        Requires periodic mode with at least one alert source enabled.

        Returns:
            int: Bitmask of SOURCE_* constants for the alerts currently active.

        Raises:
            RuntimeError: If no alert source is enabled or the CRC does not match.
        """
        if not self._alerts_enabled:
            raise RuntimeError('HS40xx alerts are not enabled')
        return self.read_periodic()['status']

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to the ALERT pin; falls back to polling at the periodic interval.

        Args:
            callback: Function called with the status bitmask.
            int_pin: Optional InputPin; defaults to ``connection.int_pin``.
        """
        self._int_callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if pin is not None and hasattr(pin, 'on_edge'):
            self._int_pin = pin
            self._int_handler = lambda p: self._dispatch_int()
            pin.on_edge(self._int_handler, 1 if self._active_high else 2)
        else:
            try:
                import _thread
            except ImportError:
                return
            stop = [False]
            self._poll_stop = stop

            def _loop():
                while not stop[0]:
                    self._dispatch_int()
                    time.sleep(self._period_s)
            _thread.start_new_thread(_loop, ())

    def off_interrupt(self):
        """Unsubscribe from the ALERT pin (or stop the polling task)."""
        self._int_callback = None
        if self._int_pin is not None and self._int_handler is not None and hasattr(self._int_pin, 'off_edge'):
            self._int_pin.off_edge(self._int_handler)
        self._int_pin = None
        self._int_handler = None
        if self._poll_stop is not None:
            self._poll_stop[0] = True
            self._poll_stop = None

    def _dispatch_int(self):
        cb = self._int_callback
        if cb is None:
            return
        try:
            cb(self.poll_interrupt())
        except Exception:
            pass

    def read_id(self):
        """Read the 32-bit sensor ID (command 0xD7, 4 bytes MSB first).

        Returns:
            int: Unique 32-bit device ID.
        """
        self._connection.write(bytes([self._CMD_READ_ID]))
        data = self._read_frame(4)
        return (data[0] << 24) | (data[1] << 16) | (data[2] << 8) | data[3]

    def soft_reset(self):
        """Stop periodic mode, send the soft reset (0xFE) and wait 500 ms."""
        self.stop_periodic()
        self._connection.write(bytes([self._CMD_SOFT_RESET]))
        time.sleep(0.5)
        self._alerts_enabled = False
        self._active_high = True

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


# HS4001 and HS4003 share one protocol, so they are plain aliases of the HS40xx
# drivers (HS4002 and the HS401x variants are only physically different).
HS4001Minimal = HS4003Minimal = HS40xxMinimal
HS4001Full = HS4003Full = HS40xxFull
