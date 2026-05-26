"""DHTxx single-wire transport for Linux kernel (python-gpiod).

Implements the bidirectional bit-bang protocol for DHT11 and DHT22 sensors
using a single GPIO data pin with an external 4.7 kΩ pull-up to VCC.

Args:
    chip_num: gpiod chip number (e.g. 0 for /dev/gpiochip0).
    line_num: GPIO line offset on that chip.
"""

import time

try:
    import gpiod
    _HAVE_GPIOD = True
except ImportError:
    _HAVE_GPIOD = False


class DHTxxTransport:
    """DHTxx single-wire transport for Linux kernel.

    A single data pin, externally pulled up to VCC via 4.7 kΩ, carries both
    the host start signal and the sensor's 40-bit response. This transport
    handles all GPIO direction switching, timing, and bit decoding.

    Args:
        chip_num: gpiod chip number (e.g. 0 for /dev/gpiochip0).
        line_num: GPIO line offset on that chip.
    """

    _T_HOST_LOW = 20000    # us -- host start signal LOW (18-30 ms)
    _T_GO = 20             # us -- host releases bus before sensor response
    _T_THRESHOLD = 40      # us -- HIGH pulse threshold: < 40 us = bit 0, >= 40 us = bit 1

    def __init__(self, chip_num, line_num):
        self._chip_num = chip_num
        self._line_num = line_num

    def read(self):
        if not _HAVE_GPIOD:
            raise TransportError("gpiod not installed")

        chip = gpiod.Chip(self._chip_num)

        line = chip.request(consumer="dhtxx", type=gpiod.LINE_REQ_DIR_OUTPUT, num_lines=1, offsets=[self._line_num])

        line.set_value(0)
        time.sleep(self._T_HOST_LOW / 1_000_000)

        line.release()
        line = chip.request(consumer="dhtxx", type=gpiod.LINE_REQ_DIR_INPUT, num_lines=1, offsets=[self._line_num])

        time.sleep(self._T_GO / 1_000_000)

        if self._wait_low(line, 1000) < 0:
            line.release()
            chip.close()
            raise TransportError("timeout")

        if self._wait_high(line, 1000) < 0:
            line.release()
            chip.close()
            raise TransportError("timeout")

        bits = []
        for _ in range(40):
            if self._wait_low(line, 1000) < 0:
                line.release()
                chip.close()
                raise TransportError("framing")
            width = self._wait_high(line, 1000)
            if width < 0:
                line.release()
                chip.close()
                raise TransportError("framing")
            bits.append(1 if width >= self._T_THRESHOLD else 0)

        line.release()
        chip.close()

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

    def _wait_low(self, line, timeout_us):
        start = time.perf_counter_ns()
        while line.get_value()[0] == 1:
            if (time.perf_counter_ns() - start) // 1000 > timeout_us:
                return -1
        return (time.perf_counter_ns() - start) // 1000

    def _wait_high(self, line, timeout_us):
        start = time.perf_counter_ns()
        while line.get_value()[0] == 0:
            if (time.perf_counter_ns() - start) // 1000 > timeout_us:
                return -1
        return (time.perf_counter_ns() - start) // 1000


class TransportError(Exception):
    pass
