"""DHTxx single-wire transport for CircuitPython.

Implements the bidirectional bit-bang protocol for DHT11 and DHT22 sensors
using a single GPIO data pin with an external 4.7 kohm pull-up to VCC.

Args:
    data_pin: digitalio.DigitalInOut configured as input (direction switched internally).
"""

import time
from digitalio import Direction


class DHTxxTransport:
    """DHTxx single-wire transport for CircuitPython.

    A single data pin, externally pulled up to VCC via 4.7 kohm, carries both
    the host start signal and the sensor's 40-bit response. This transport
    handles all GPIO direction switching, timing, and bit decoding.

    Args:
        data_pin: digitalio.DigitalInOut instance for the data line.
    """

    _T_HOST_LOW = 20000
    _T_GO = 20
    _T_THRESHOLD = 40

    def __init__(self, data_pin):
        self._pin = data_pin
        self._pin.direction = Direction.OUTPUT

    def read(self):
        self._pin.direction = Direction.OUTPUT
        self._pin.value = False
        time.sleep_us(self._T_HOST_LOW)
        self._pin.direction = Direction.INPUT
        time.sleep_us(self._T_GO)

        if self._wait_low(1000) < 0:
            raise TransportError("timeout")

        if self._wait_high(1000) < 0:
            raise TransportError("timeout")

        bits = []
        for _ in range(40):
            if self._wait_low(1000) < 0:
                raise TransportError("framing")
            width = self._wait_high(1000)
            if width < 0:
                raise TransportError("framing")
            bits.append(1 if width >= self._T_THRESHOLD else 0)

        result = 0
        for b in bits:
            result = (result << 1) | b

        return bytes([
            (result >> 32) & 0xFF,
            (result >> 24) & 0xFF,
            (result >> 16) & 0xFF,
            (result >> 8) & 0xFF,
            result & 0xFF,
        ])

    def close(self):
        pass

    def _wait_low(self, timeout_us):
        start = time.monotonic_ns()
        while self._pin.value:
            if (time.monotonic_ns() - start) // 1000 > timeout_us:
                return -1
        return (time.monotonic_ns() - start) // 1000

    def _wait_high(self, timeout_us):
        start = time.monotonic_ns()
        while not self._pin.value:
            if (time.monotonic_ns() - start) // 1000 > timeout_us:
                return -1
        return (time.monotonic_ns() - start) // 1000


class TransportError(Exception):
    pass
