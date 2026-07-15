import utime


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
    """DHTxx single-wire transport for MicroPython (wraps a machine.Pin).

    Implements the host side of the DHT11 / DHT22 single-wire protocol: a
    bidirectional DATA line, externally pulled up to VCC via a 4.7 kΩ resistor.
    The transport switches the pin's direction as needed to drive the start
    signal and then sample the sensor's 40-bit response. All timing is done
    with `utime.ticks_us()` and busy-wait loops.

    Args:
        data_pin: machine.Pin instance (the transport reconfigures its
                  direction internally — do not bind it to a fixed direction).
    """

    _START_LOW_MS       = 20
    _RESPONSE_TIMEOUT_US = 200
    _BIT_TIMEOUT_US     = 200
    _BIT_THRESHOLD_US   = 40

    def __init__(self, data_pin):
        self._pin = data_pin
        self._pin.init(self._pin.IN)

    def _drive_low(self):
        self._pin.init(self._pin.OUT)
        self._pin.value(0)

    def _release_bus(self):
        self._pin.init(self._pin.IN)

    def _measure_pulse(self, level, timeout_us):
        """Block until the line is at `level`, then time how long it stays there.

        Returns the pulse duration in µs, or -1 on timeout. Used for both
        the sensor response phase and per-bit HIGH pulses.
        """
        start = utime.ticks_us()
        while self._pin.value() != level:
            if utime.ticks_diff(utime.ticks_us(), start) > timeout_us:
                return -1
        pulse_start = utime.ticks_us()
        while self._pin.value() == level:
            if utime.ticks_diff(utime.ticks_us(), pulse_start) > timeout_us:
                return -1
        return utime.ticks_diff(utime.ticks_us(), pulse_start)

    def read(self):
        """Execute the full DHTxx transaction and return the raw 5-byte frame.

        Returns:
            bytes: Exactly 5 bytes — [hum_int, hum_dec, temp_int, temp_dec, checksum].

        Raises:
            DHTxxError: On timeout (sensor did not respond) or framing error
                        (fewer than 40 bits received).
        """
        self._drive_low()
        utime.sleep_ms(self._START_LOW_MS)
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
        """Release the pin. MicroPython Pin has no explicit deinit; reset to input."""
        try:
            self._pin.init(self._pin.IN)
        except Exception:
            pass
