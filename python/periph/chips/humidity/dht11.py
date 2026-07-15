"""DHT11 temperature and humidity sensor (ASAIR).

Implements the DHT11 single-wire bidirectional GPIO protocol. The driver is
platform-agnostic; it accepts a small platform-specific pin adapter that
exposes ``set_output()``, ``set_input()``, ``drive(low)``, and ``read()``.

Supported targets (each provides its own adapter):
- MicroPython:   ``periph.transport.dht11_micropython.DHT11Pin`` wraps a ``machine.Pin``
- CircuitPython: ``periph.transport.dht11_circuitpython.DHT11Pin`` wraps a ``digitalio.DigitalInOut``
- Linux:         ``periph.transport.dht11_linux.DHT11Pin`` wraps a ``gpiod.LineRequest`` + line offset

The protocol is timing-critical (microsecond resolution) and works best on
embedded targets. On Linux the bit-bang may sporadically fail under load;
use ``DHT11Full.read_retry()`` to recover from transient checksum errors.
"""


def _micros():
    import time
    return time.ticks_us()


def _micros_diff(start, end):
    import time
    return time.ticks_diff(end, start)


def _sleep_ms(ms):
    import time
    time.sleep_ms(ms)


def _sleep_us(us):
    import time
    time.sleep_us(us)


class DHT11Minimal:
    """DHT11 temperature and humidity sensor — minimal interface.

    Performs a full protocol transaction (host start signal, sensor response,
    40-bit data frame, checksum verification) and returns temperature and
    humidity. Single read attempt; raises an exception on checksum mismatch.

    The driver owns the pin adapter and re-initialises it on every call.
    Callers must respect the 2-second minimum sampling interval between reads;
    the driver does not enforce this automatically.

    Args:
        data_pin: Pin adapter object exposing ``set_output()``, ``set_input()``,
                  ``drive(low: bool)``, and ``read() -> bool``. See the
                  ``dht11_<platform>`` modules for adapters.
    """

    def __init__(self, data_pin):
        self._pin = data_pin

    def _drive_low(self):
        self._pin.drive(False)

    def _drive_high(self):
        self._pin.drive(True)

    def _read_value(self):
        return self._pin.read()

    def _wait_low(self, timeout_us):
        start = _micros()
        while self._read_value() is True:
            if _micros_diff(start, _micros()) > timeout_us:
                return False
        return True

    def _wait_high(self, timeout_us):
        start = _micros()
        while self._read_value() is False:
            if _micros_diff(start, _micros()) > timeout_us:
                return False
        return True

    def _measure_high(self):
        start = _micros()
        while self._read_value() is True:
            if _micros_diff(start, _micros()) > 100:
                break
        return _micros_diff(start, _micros())

    def _read_frame(self):
        self._pin.set_output()
        self._drive_low()
        _sleep_ms(20)
        self._pin.set_input()
        _sleep_us(30)
        if not self._wait_low(200):
            raise OSError("DHT11 sensor did not respond (no LOW)")
        if not self._wait_high(200):
            raise OSError("DHT11 sensor did not release response LOW")
        if not self._wait_low(200):
            raise OSError("DHT11 sensor did not start data phase")
        bits = 0
        for _ in range(40):
            if not self._wait_high(200):
                raise OSError("DHT11 bit LOW phase missing")
            high_us = self._measure_high()
            bits = (bits << 1) | (1 if high_us > 40 else 0)
        bytes_ = [(bits >> (8 * (4 - i))) & 0xFF for i in range(5)]
        checksum = (bytes_[0] + bytes_[1] + bytes_[2] + bytes_[3]) & 0xFF
        if checksum != bytes_[4]:
            raise OSError("DHT11 checksum mismatch")
        return bytes(bytes_)

    def read(self):
        """Perform a full protocol read and return temperature and humidity.

        Returns:
            tuple: ``(temperature_c, humidity_rh)`` as floats.

        Raises:
            OSError: On sensor timeout or checksum mismatch.
        """
        raw = self._read_frame()
        sign = -1 if (raw[3] & 0x80) else 1
        temp_dec = raw[3] & 0x7F
        temperature_c = sign * (raw[2] + temp_dec / 10.0)
        humidity_rh = raw[0] + raw[1] / 10.0
        return (temperature_c, humidity_rh)


class DHT11Full(DHT11Minimal):
    """DHT11 full interface — extends DHT11Minimal with retry and raw access.

    Adds separate temperature/humidity accessors, automatic retry on checksum
    failure, and access to the raw 5-byte frame.

    Args:
        data_pin: Pin adapter object (same as DHT11Minimal).
    """

    def read_temperature(self):
        """Read temperature in degrees Celsius.

        Returns:
            float: Temperature in °C.
        """
        return self.read()[0]

    def read_humidity(self):
        """Read relative humidity.

        Returns:
            float: Relative humidity in %RH.
        """
        return self.read()[1]

    def read_retry(self, max_retries=3):
        """Read with automatic retry on checksum failure.

        Args:
            max_retries: Maximum number of attempts (default 3).

        Returns:
            tuple: ``(temperature_c, humidity_rh)`` as floats.

        Raises:
            OSError: After all retries have been exhausted.
        """
        last_err = None
        for _ in range(max_retries):
            try:
                return self.read()
            except OSError as e:
                last_err = e
        raise last_err

    def read_raw(self):
        """Read and return the raw 5-byte frame without interpretation.

        Returns:
            bytes: 5-byte frame ``[hum_int, hum_dec, temp_int, temp_dec, checksum]``.

        Raises:
            OSError: On sensor timeout or checksum mismatch.
        """
        return self._read_frame()
