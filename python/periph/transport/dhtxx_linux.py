import time

import gpiod
from gpiod.line import Direction, Value, Drive, Edge


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
    """DHTxx single-wire transport for Linux (wraps libgpiod v2 line requests).

    Implements the host side of the DHT11 / DHT22 single-wire protocol: a
    bidirectional DATA line, externally pulled up to VCC via a 4.7 kΩ resistor.
    Direction switching goes through the kernel's gpiod request lifecycle
    (release and re-request with a different direction flag) and is therefore
    expensive — this is the timing bottleneck on Linux, see
    `specs/transport_dhtxx.md` for details.

    Optional two-pin open-drain variant: pass `line_num_out` to request a
    second line wired to the same physical DATA net. That line is requested
    once as open-drain output and used to actively pull the bus LOW. The
    original line stays as input for the lifetime of the transport. This
    avoids the release/re-request entirely.

    Args:
        chip_num:    GPIO chip number (e.g. 0 for /dev/gpiochip0).
        line_num:    GPIO line offset on that chip (input by default).
        line_num_out: Optional second GPIO line offset, open-drain output, on
                      the same chip. Enables the two-pin variant.
    """

    _START_LOW_MS       = 20
    _RELEASE_WAIT_US    = 30
    _RESPONSE_TIMEOUT_US = 200
    _BIT_TIMEOUT_US     = 200
    _BIT_THRESHOLD_US   = 40

    def __init__(self, chip_num, line_num, line_num_out=None):
        self._chip_num = chip_num
        self._line_num = line_num
        self._line_num_out = line_num_out
        self._input_request = None
        self._output_request = None
        self._two_pin = line_num_out is not None
        self._open()

    def _open(self):
        chip = gpiod.Chip("/dev/gpiochip{}".format(self._chip_num))
        if self._two_pin:
            input_settings = gpiod.LineSettings(direction=Direction.INPUT, edge_detection=Edge.NONE)
            output_settings = gpiod.LineSettings(
                direction=Direction.OUTPUT,
                drive=Drive.OPEN_DRAIN,
                output_value=Value.INACTIVE,
            )
            self._input_request = chip.request_lines(
                consumer="dhtxx-in",
                line_config={self._line_num: input_settings},
            )
            self._output_request = chip.request_lines(
                consumer="dhtxx-out",
                line_config={self._line_num_out: output_settings},
            )
        else:
            settings = gpiod.LineSettings(direction=Direction.INPUT, edge_detection=Edge.NONE)
            self._input_request = chip.request_lines(
                consumer="dhtxx",
                line_config={self._line_num: settings},
            )

    def _request_output(self):
        """Release the input line and request it as output (single-pin mode)."""
        if self._input_request is not None:
            self._input_request.release()
            self._input_request = None
        chip = gpiod.Chip("/dev/gpiochip{}".format(self._chip_num))
        settings = gpiod.LineSettings(
            direction=Direction.OUTPUT,
            output_value=Value.ACTIVE,
        )
        self._output_request = chip.request_lines(
            consumer="dhtxx-out",
            line_config={self._line_num: settings},
        )

    def _request_input(self):
        """Release the output line and request it as input (single-pin mode)."""
        if self._output_request is not None:
            self._output_request.release()
            self._output_request = None
        chip = gpiod.Chip("/dev/gpiochip{}".format(self._chip_num))
        settings = gpiod.LineSettings(direction=Direction.INPUT, edge_detection=Edge.NONE)
        self._input_request = chip.request_lines(
            consumer="dhtxx-in",
            line_config={self._line_num: settings},
        )

    def _drive_low(self):
        if self._two_pin:
            self._output_request.set_value(self._line_num_out, Value.ACTIVE)
        else:
            if self._output_request is None:
                self._request_output()

    def _release_bus(self):
        if self._two_pin:
            self._output_request.set_value(self._line_num_out, Value.INACTIVE)
        else:
            self._request_input()

    def _wait_for_low_pulse_us(self, timeout_us):
        """Wait for the line to go LOW, then return the LOW-pulse duration in µs.

        Returns -1 on timeout. Used to measure the start of each data bit.
        """
        deadline = time.perf_counter_ns() + timeout_us * 1000
        while self._input_request.get_value(self._line_num) == Value.INACTIVE:
            if time.perf_counter_ns() >= deadline:
                return -1
        if time.perf_counter_ns() >= deadline:
            return -1
        low_start = time.perf_counter_ns()
        while self._input_request.get_value(self._line_num) == Value.ACTIVE:
            if time.perf_counter_ns() >= deadline:
                return -1
        return (time.perf_counter_ns() - low_start) // 1000

    def _wait_for_high_pulse_us(self, timeout_us):
        """Wait for the line to go HIGH, then return the HIGH-pulse duration in µs.

        Returns -1 on timeout. Used to decode data bits by measuring the HIGH
        pulse after the bit's start-low pulse (bit-0 ≈ 24 µs, bit-1 ≈ 71 µs).
        """
        deadline = time.perf_counter_ns() + timeout_us * 1000
        while self._input_request.get_value(self._line_num) == Value.INACTIVE:
            if time.perf_counter_ns() >= deadline:
                return -1
        high_start = time.perf_counter_ns()
        while self._input_request.get_value(self._line_num) == Value.ACTIVE:
            if time.perf_counter_ns() >= deadline:
                return -1
        return (time.perf_counter_ns() - high_start) // 1000

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
        elapsed = self._wait_for_high_pulse_us(self._RELEASE_WAIT_US)
        if elapsed < 0:
            raise DHTxxError("timeout", "sensor did not release bus within {} us".format(self._RELEASE_WAIT_US))
        elapsed = self._wait_for_low_pulse_us(self._RESPONSE_TIMEOUT_US)
        if elapsed < 0:
            raise DHTxxError("timeout", "sensor did not pull DATA low within {} us".format(self._RESPONSE_TIMEOUT_US))
        elapsed = self._wait_for_high_pulse_us(self._RESPONSE_TIMEOUT_US)
        if elapsed < 0:
            raise DHTxxError("timeout", "sensor did not release after response low within {} us".format(self._RESPONSE_TIMEOUT_US))

        frame = bytearray(5)
        for byte_idx in range(5):
            byte = 0
            for bit_idx in range(8):
                elapsed = self._wait_for_low_pulse_us(self._BIT_TIMEOUT_US)
                if elapsed < 0:
                    raise DHTxxError("framing", "bit {} start-low missing".format(byte_idx * 8 + bit_idx))
                elapsed = self._wait_for_high_pulse_us(self._BIT_TIMEOUT_US)
                if elapsed < 0:
                    raise DHTxxError("framing", "bit {} high-pulse missing".format(byte_idx * 8 + bit_idx))
                byte = (byte << 1) | (1 if elapsed > self._BIT_THRESHOLD_US else 0)
            frame[byte_idx] = byte
        return bytes(frame)

    def close(self):
        """Release all held line requests back to the kernel."""
        if self._input_request is not None:
            self._input_request.release()
            self._input_request = None
        if self._output_request is not None:
            self._output_request.release()
            self._output_request = None
