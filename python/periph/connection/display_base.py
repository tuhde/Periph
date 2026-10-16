import time

try:
    from time import ticks_ms, ticks_diff
except ImportError:  # CPython / CircuitPython have no ticks_*; time.monotonic() is available
    def ticks_ms():
        return int(time.monotonic() * 1000)

    def ticks_diff(a, b):
        return a - b


class WireMode:
    """How the display controller's data pin is wired to the host.

    SIMPLEX:         MOSI -> DIN, write only; read_data() is not available.
    HALF_DUPLEX_HW:  the controller's DIN/DOUT pin shared with MOSI, read through the
                     host SPI peripheral's hardware 3-wire (half-duplex) mode. Only on
                     platforms that have one (Linux spidev SPI_3WIRE).
    HALF_DUPLEX_PIN: MOSI -- resistor -- MISO joined at DIN/DOUT, read through a
                     full-duplex (hardware or software) SPI master sampling MISO.
    """

    SIMPLEX = 0
    HALF_DUPLEX_HW = 1
    HALF_DUPLEX_PIN = 2


class DisplayConnectionBase:
    """Platform-independent part of the display connection.

    Bundles an SPI link to a display controller (OLED: SH1106; e-paper:
    SSD1683, UC8276C; ...) with its side-band lines: D/C# (command/data),
    RES# (hardware reset) and BUSY. RES# and BUSY are optional: an OLED has no
    BUSY line, and RES# may be tied high. Chip drivers call send_command() /
    send_data() / read_data() / reset() / wait_busy() and never touch D/C,
    RES, BUSY or CS themselves.

    The BUSY polarity differs between controllers, so it is chip-level
    knowledge: wait_busy() takes it as an argument and busy_level() returns the
    raw level.

    Chip select is hardware CS only: every send_command() / send_data() /
    read_data() byte group is its own SPI transfer, and a platform subclass
    frames each one with CS. A command and its parameters are therefore
    separate CS windows; read_data() uses one CS window per byte, as the
    controllers' read timing diagrams show.

    Platform subclasses implement _spi_write() and _spi_read().

    Args:
        dc: OutputPin for D/C# (low = command, high = data).
        res: OutputPin for RES# (active low hardware reset), or None if not wired;
            reset() is then a no-op.
        busy: InputPin for BUSY, read with is_high(), or None if the controller
            has no BUSY line; wait_busy() then returns True immediately.
        wire_mode: A WireMode value (default SIMPLEX).
        reset_pulse_us: RES# low time in microseconds (default 200, which
            satisfies the UC8276C 50 us and SSD1683 200 us minimums).
        reset_settle_ms: Wait after RES# is released in milliseconds
            (default 10; the UC8276C needs more than 1 ms).
        en_pin: Optional OutputPin for hardware enable/power control.
        en_active_high: True if the EN pin is active-high (default); False for active-low.
    """

    def __init__(self, dc, res, busy, wire_mode=WireMode.SIMPLEX, reset_pulse_us=200,
                 reset_settle_ms=10, en_pin=None, en_active_high=True):
        if wire_mode not in (WireMode.SIMPLEX, WireMode.HALF_DUPLEX_HW, WireMode.HALF_DUPLEX_PIN):
            raise ValueError('unknown wire_mode %r' % (wire_mode,))
        self._dc = dc
        self._res = res
        self._busy = busy
        self.wire_mode = wire_mode
        self._reset_pulse_us = reset_pulse_us
        self._reset_settle_ms = reset_settle_ms
        self.en_pin = en_pin
        self.en_active_high = en_active_high
        self._enabled = True
        self._dc.set(False)
        if self._res is not None:
            self._res.set(True)

    # -- platform hooks ---------------------------------------------------------------

    def _spi_write(self, data):
        """Send bytes as one CS-framed SPI transfer."""
        raise NotImplementedError

    def _spi_read(self, length):
        """Clock in `length` bytes as one CS-framed SPI transfer and return them."""
        raise NotImplementedError

    # -- power / gating ---------------------------------------------------------------

    def enable(self):
        """Resume operation; asserts the hardware EN pin if wired."""
        self._enabled = True
        if self.en_pin:
            self.en_pin.set(self.en_active_high)

    def disable(self):
        """Gate all bus operations; de-asserts the hardware EN pin if wired."""
        self._enabled = False
        if self.en_pin:
            self.en_pin.set(not self.en_active_high)

    def is_enabled(self):
        """Return the current software-gate state.

        Returns:
            bool: True if enabled.
        """
        return self._enabled

    # -- control lines ----------------------------------------------------------------

    def reset(self):
        """Hardware reset: RES# low for reset_pulse_us, then high and wait reset_settle_ms.

        Does not wait for BUSY; chip drivers call wait_busy() afterwards with
        their polarity. No-op if this connection is disabled.
        """
        if not self._enabled or self._res is None:
            return
        self._res.set(False)
        time.sleep(self._reset_pulse_us / 1000000.0)
        self._res.set(True)
        time.sleep(self._reset_settle_ms / 1000.0)

    def delay_ms(self, ms):
        """Block for `ms` milliseconds (for chips without a BUSY pin, e.g. a power-up wait).

        Args:
            ms: Time in milliseconds.
        """
        time.sleep(ms / 1000.0)

    def busy_level(self):
        """Read the raw level of the BUSY line.

        Returns:
            bool: True when BUSY is at logic high.

        Raises:
            RuntimeError: If no BUSY pin was given.
        """
        if self._busy is None:
            raise RuntimeError('no BUSY pin configured')
        return bool(self._busy.is_high())

    def wait_busy(self, busy_active_high, timeout_ms=None):
        """Poll BUSY until it is no longer active.

        Args:
            busy_active_high: True if the controller drives BUSY high while busy
                (SSD1683), False if it drives it low (UC8276C BUSY_N).
            timeout_ms: Give up after this many milliseconds; None waits forever.

        Returns:
            bool: True when the controller became ready (or has no BUSY pin),
            False on timeout.
        """
        if self._busy is None:
            return True
        start = ticks_ms()
        while bool(self._busy.is_high()) == bool(busy_active_high):
            if timeout_ms is not None and ticks_diff(ticks_ms(), start) >= timeout_ms:
                return False
            time.sleep(0.001)
        return True

    # -- bus operations ---------------------------------------------------------------

    def send_command(self, cmd, data=b''):
        """Send a command byte (D/C# low), then optional parameter bytes (D/C# high).

        The command byte and the parameters are separate SPI transfers (separate
        CS windows). No-op if this connection is disabled.

        Args:
            cmd: Command byte.
            data: Parameter bytes (default none).
        """
        if not self._enabled:
            return
        self._dc.set(False)
        self._spi_write(bytes([cmd]))
        if data:
            self._dc.set(True)
            self._spi_write(bytes(data))

    def send_data(self, data):
        """Send data bytes (D/C# high) as one SPI transfer. No-op if disabled.

        Args:
            data: Bytes to send, e.g. a pixel plane.
        """
        if not self._enabled:
            return
        self._dc.set(True)
        self._spi_write(bytes(data))

    def read_data(self, length):
        """Read `length` bytes the controller drives after a read command (D/C# high).

        One CS window per byte. Disabled connections return zero bytes.

        Args:
            length: Number of bytes to read.

        Returns:
            bytes: The bytes read.

        Raises:
            RuntimeError: In SIMPLEX wire mode, which has no read path.
        """
        if self.wire_mode == WireMode.SIMPLEX:
            raise RuntimeError('read_data() is not available in SIMPLEX wire mode')
        if not self._enabled:
            return bytes(length)
        self._dc.set(True)
        out = bytearray()
        for _ in range(length):
            out += self._spi_read(1)
        return bytes(out)

    def close(self):
        """Release platform resources (SPI device, GPIO lines). No-op in the base."""
        pass
