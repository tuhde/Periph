"""DHTxx single-wire transport for MicroPython.

Implements the bidirectional bit-bang protocol for DHT11 and DHT22 sensors
using a single GPIO data pin with an external 4.7 kΩ pull-up to VCC.

Args:
    data_pin: machine.Pin configured as input (direction is switched internally).
"""

import utime


class DHTxxTransport:
    """DHTxx single-wire transport for MicroPython.

    A single data pin, externally pulled up to VCC via 4.7 kΩ, carries both
    the host start signal and the sensor's 40-bit response. This transport
    handles all GPIO direction switching, timing, and bit decoding.

    Args:
        data_pin: machine.Pin instance for the data line.
    """

    _T_HOST_LOW = 20000    # us -- host start signal LOW (18-30 ms)
    _T_GO = 20             # us -- host releases bus before sensor response
    _T_THRESHOLD = 40      # us -- HIGH pulse threshold: < 40 us = bit 0, >= 40 us = bit 1

    def __init__(self, data_pin):
        self._pin = data_pin
        self._pin.init(utime.Pin.OUT)

    def read(self):
        self._pin.init(utime.Pin.OUT)
        self._pin.value(0)
        utime.sleep_us(self._T_HOST_LOW)
        self._pin.init(utime.Pin.IN)
        utime.sleep_us(self._T_GO)

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
        start = utime.ticks_us()
        while self._pin.value() == 1:
            if utime.ticks_diff(utime.ticks_us(), start) > timeout_us:
                return -1
        return utime.ticks_diff(utime.ticks_us(), start)

    def _wait_high(self, timeout_us):
        start = utime.ticks_us()
        while self._pin.value() == 0:
            if utime.ticks_diff(utime.ticks_us(), start) > timeout_us:
                return -1
        return utime.ticks_diff(utime.ticks_us(), start)


class TransportError(Exception):
    pass
