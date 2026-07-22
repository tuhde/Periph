class DHT11Error(Exception):
    """Raised on checksum error when reading a DHT11 frame."""
    pass


class DHT11Minimal:
    """DHT11 combined temperature and humidity sensor — minimal interface.

    The DHT11 returns a 40-bit reading (humidity integer + decimal,
    temperature integer + decimal, checksum) over a single bidirectional
    data line. The driver accepts a `DHTxxTransport` instance that handles
    the underlying single-wire protocol; this class is responsible only
    for validating the frame and converting it to engineering units.

    Default configuration (baked in at construction):
        - Single read attempt; raises on checksum mismatch
        - Caller responsible for respecting the ≥ 2 s sampling interval

    Args:
        transport: Configured `DHTxxTransport` bound to the chip's DATA pin.
    """

    def __init__(self, transport):
        self._transport = transport

    def read(self):
        """Read both temperature and humidity in a single transaction.

        Returns:
            tuple: (temperature_C, humidity_RH) as two floats.

        Raises:
            DHT11Error: If the received frame's checksum is invalid.
        """
        frame = self._transport.read()
        return self._decode(frame)

    def _decode(self, frame):
        if len(frame) != 5:
            raise DHT11Error("frame must be 5 bytes, got {}".format(len(frame)))
        hum_int, hum_dec, temp_int, temp_dec, checksum = frame[0], frame[1], frame[2], frame[3], frame[4]
        expected = (hum_int + hum_dec + temp_int + temp_dec) & 0xFF
        if expected != checksum:
            raise DHT11Error("checksum mismatch: expected 0x{:02X}, got 0x{:02X}".format(expected, checksum))
        humidity = hum_int + hum_dec / 10.0
        sign = -1 if (temp_dec & 0x80) else 1
        temp_dec_value = temp_dec & 0x7F
        temperature = sign * (temp_int + temp_dec_value / 10.0)
        return (temperature, humidity)


class DHT11Full(DHT11Minimal):
    """DHT11 full interface — extends DHT11Minimal with retry, raw access, and convenience methods.

    Adds a configurable-retry read, separate `read_temperature()` / `read_humidity()`
    accessors, and a `read_raw()` method that returns the unprocessed 5-byte frame.

    Args:
        transport: Configured `DHTxxTransport` bound to the chip's DATA pin.
        max_retries: Default retry count for `read_retry` (default 3).
    """

    def __init__(self, transport, max_retries=3):
        super().__init__(transport)
        self._max_retries = max_retries

    def read_temperature(self):
        """Read temperature in a single transaction.

        Returns:
            float: Temperature in degrees Celsius.
        """
        t, _ = self.read()
        return t

    def read_humidity(self):
        """Read humidity in a single transaction.

        Returns:
            float: Humidity in %RH.
        """
        _, h = self.read()
        return h

    def read_retry(self, max_retries=None):
        """Read both values, retrying on checksum error.

        Args:
            max_retries: Maximum number of read attempts (default: constructor value).

        Returns:
            tuple: (temperature_C, humidity_RH) as two floats.

        Raises:
            DHT11Error: If all attempts fail with a checksum error.
        """
        if max_retries is None:
            max_retries = self._max_retries
        last_error = None
        for _ in range(max_retries):
            try:
                return self.read()
            except DHT11Error as e:
                last_error = e
        raise DHT11Error("read_retry exhausted after {} attempts: {}".format(max_retries, last_error))

    def read_raw(self):
        """Read the raw 5-byte frame without checksum validation.

        Returns:
            bytes: 5-byte frame — [hum_int, hum_dec, temp_int, temp_dec, checksum].

        Raises:
            DHT11Error: If the frame's checksum is invalid.
        """
        frame = self._transport.read()
        if len(frame) != 5:
            raise DHT11Error("frame must be 5 bytes, got {}".format(len(frame)))
        hum_int, hum_dec, temp_int, temp_dec, checksum = frame[0], frame[1], frame[2], frame[3], frame[4]
        expected = (hum_int + hum_dec + temp_int + temp_dec) & 0xFF
        if expected != checksum:
            raise DHT11Error("checksum mismatch: expected 0x{:02X}, got 0x{:02X}".format(expected, checksum))
        return frame
