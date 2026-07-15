import time


class DHTxxError(Exception):
    """Raised when the DHTxx transport cannot complete a read.

    The transport distinguishes two failure modes:
        - timeout: the sensor did not pull DATA LOW after the host start signal
        - framing: fewer than 40 bit pulses were received before the bus returned idle
    """

    def __init__(self, kind, detail=""):
        self.kind = kind
        self.detail = detail
        super().__init__("{}: {}".format(kind, detail) if detail else kind)


class DHTxxTransport:
    """DHTxx single-wire transport for CircuitPython (wraps a digitalio.DigitalInOut).

    Implements the host side of the DHT11 / DHT22 single-wire protocol: a
    bidirectional DATA line, externally pulled up to VCC via a 4.7 kΩ resistor.
    The transport switches the pin's direction as needed to drive the start
    signal and then sample the sensor's 40-bit response. All timing is done
    with `time.monotonic_ns()` and busy-wait loops.

    Args:
        data_pin: digitalio.DigitalInOut instance. The transport reconfigures
                  its direction internally.
    """

    _START_LOW_MS       = 20
    _RESPONSE_TIMEOUT_US = 200
    _BIT_TIMEOUT_US     = 200
    _BIT_THRESHOLD_US   = 40

    def __init__(self, data_pin):
        import digitalio
        self._pin = data_pin
        self._digitalio = digitalio
        self._pin.direction = digitalio.Direction.INPUT

    def _drive_low(self):
        self._pin.direction = self._digitalio.Direction.OUTPUT
        self._pin.value = False

    def _release_bus(self):
        self._pin.direction = self._digitalio.Direction.INPUT

    def _ns_to_us(self, ns):
        return ns // 1000

    def _measure_pulse(self, level, timeout_us):
        """Block until the line is at `level`, then time how long it stays there.

        Returns the pulse duration in µs, or -1 on timeout.
        """
        timeout_ns = timeout_us * 1000
        start = time.monotonic_ns()
        while self._pin.value != bool(level):
            if time.monotonic_ns() - start > timeout_ns:
                return -1
        pulse_start = time.monotonic_ns()
        while self._pin.value == bool(level):
            if time.monotonic_ns() - pulse_start > timeout_ns:
                return -1
        return self._ns_to_us(time.monotonic_ns() - pulse_start)

    def read(self):
        """Execute the full DHTxx transaction and return the raw 5-byte frame.

        Returns:
            bytes: Exactly 5 bytes — [hum_int, hum_dec, temp_int, temp_dec, checksum].

        Raises:
            DHTxxError: On timeout (sensor did not respond) or framing error
                        (fewer than 40 bits received).
        """
        self._drive_low()
        time.sleep(self._START_LOW_MS / 1000.0)
        self._release_bus()
        elapsed = self._measure_pulse(0, self._RESPONSE_TIMEOUT_US)
        if elapsed < 0:
            raise DHTxxError("timeout", "sensor did not pull DATA low within {} us".format(self._RESPONSE_TIMEOUT_US))
        elapsed = self._measure_pulse(1, self._RESPONSE_TIMEOUT_US)
        if elapsed < 0:
            raise DHTxxError("timeout", "sensor did not release after response low within {} us".format(self._RESPONSE_TIMEOUT_US))

        frame = bytearray(5)
        for byte_idx in range(5):
            byte = 0
            for bit_idx in range(8):
                elapsed = self._measure_pulse(0, self._BIT_TIMEOUT_US)
                if elapsed < 0:
                    raise DHTxxError("framing", "bit {} start-low missing".format(byte_idx * 8 + bit_idx))
                elapsed = self._measure_pulse(1, self._BIT_TIMEOUT_US)
                if elapsed < 0:
                    raise DHTxxError("framing", "bit {} high-pulse missing".format(byte_idx * 8 + bit_idx))
                byte = (byte << 1) | (1 if elapsed > self._BIT_THRESHOLD_US else 0)
            frame[byte_idx] = byte
        return bytes(frame)

    def close(self):
        """Deinit the pin and release it back to the system."""
        try:
            self._pin.deinit()
        except Exception:
            pass
