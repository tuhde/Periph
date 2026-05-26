"""DHT11 humidity/temperature sensor driver.

DHT11 is a low-cost combined temperature and humidity sensor with factory-calibrated
digital output. Each read returns the result of the sensor's most recent completed
measurement, not a fresh instantaneous conversion.
"""


class DHT11Minimal:
    """DHT11 minimal interface -- reads temperature and humidity in one call.

    Args:
        transport: Configured DHTxxTransport instance (data pin).
    """

    def __init__(self, transport):
        self._transport = transport

    def read(self):
        """Read temperature and humidity.

        Returns:
            tuple: (temperature_C, humidity_RH) as floats.

        Raises:
            ValueError: Checksum mismatch.
        """
        frame = self._transport.read()

        hum_int  = frame[0]
        hum_dec  = frame[1]
        temp_int = frame[2]
        temp_dec = frame[3]
        checksum = frame[4]

        if (hum_int + hum_dec + temp_int + temp_dec) & 0xFF != checksum:
            raise ValueError("checksum mismatch")

        humidity = hum_int + hum_dec / 10.0
        sign = -1 if (temp_dec & 0x80) else 1
        temp_dec_value = temp_dec & 0x7F
        temperature = sign * (temp_int + temp_dec_value / 10.0)

        return (temperature, humidity)


class DHT11Full(DHT11Minimal):
    """DHT11 full interface -- extends Minimal with additional methods.

    Args:
        transport: Configured DHTxxTransport instance (data pin).
    """

    def __init__(self, transport):
        super().__init__(transport)

    def read_temperature(self):
        """Read temperature in degrees Celsius.

        Returns:
            float: Temperature in °C.
        """
        return self.read()[0]

    def read_humidity(self):
        """Read relative humidity in percent.

        Returns:
            float: Humidity in %RH.
        """
        return self.read()[1]

    def read_retry(self, max_retries=3):
        """Read with retry on checksum error.

        Args:
            max_retries: Maximum retry attempts (default 3).

        Returns:
            tuple: (temperature_C, humidity_RH).

        Raises:
            ValueError: All retries exhausted.
        """
        for _ in range(max_retries):
            try:
                return self.read()
            except ValueError:
                pass
        raise ValueError("all retries exhausted")

    def read_raw(self):
        """Return raw 5-byte frame without interpretation.

        Returns:
            bytes: Raw 5-byte frame.

        Raises:
            ValueError: Checksum mismatch.
        """
        frame = self._transport.read()

        hum_int  = frame[0]
        hum_dec  = frame[1]
        temp_int = frame[2]
        temp_dec = frame[3]
        checksum = frame[4]

        if (hum_int + hum_dec + temp_int + temp_dec) & 0xFF != checksum:
            raise ValueError("checksum mismatch")

        return frame
