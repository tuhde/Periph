from .display_base import DisplayConnectionBase, WireMode


class DisplayConnection(DisplayConnectionBase):
    """Display connection (SPI + D/C#, RES#, BUSY) for CircuitPython.

    Wraps a busio.SPI (hardware) or bitbangio.SPI (software) instance plus the
    CS#, D/C#, RES# and BUSY lines. The bus is locked and configured (mode 0)
    around every transfer, and CS# is driven manually (idles high).

    Wire modes: SIMPLEX needs only a write-capable SPI. HALF_DUPLEX_PIN needs
    an SPI whose MISO is wired to the controller's DIN/DOUT line (MOSI joined to
    it through a series resistor); the connection reads with readinto().
    HALF_DUPLEX_HW is not available through busio.SPI and raises ValueError.

    Args:
        spi: busio.SPI or bitbangio.SPI instance.
        cs: OutputPin for CS# (active low).
        dc: OutputPin for D/C# (low = command, high = data).
        res: OutputPin for RES# (active low), or None if not wired.
        busy: InputPin for BUSY, read with is_high(), or None if the controller has no BUSY line.
        baudrate: SPI clock in Hz (default 4 000 000).
        wire_mode: A WireMode value (default SIMPLEX).
        reset_pulse_us: RES# low time in microseconds (default 200).
        reset_settle_ms: Wait after RES# is released in milliseconds (default 10).
        en_pin: Optional OutputPin for hardware enable/power control.
        en_active_high: True if the EN pin is active-high (default); False for active-low.
    """

    def __init__(self, spi, cs, dc, res, busy, baudrate=4000000, wire_mode=WireMode.SIMPLEX,
                 reset_pulse_us=200, reset_settle_ms=10, en_pin=None, en_active_high=True):
        if wire_mode == WireMode.HALF_DUPLEX_HW:
            raise ValueError('HALF_DUPLEX_HW is not available on CircuitPython; use HALF_DUPLEX_PIN')
        if wire_mode == WireMode.HALF_DUPLEX_PIN and not hasattr(spi, 'readinto'):
            raise ValueError('HALF_DUPLEX_PIN needs an SPI object with readinto() and MISO wired')
        super().__init__(dc, res, busy, wire_mode, reset_pulse_us, reset_settle_ms,
                         en_pin, en_active_high)
        self._spi = spi
        self._cs = cs
        self._baudrate = baudrate
        self._cs.set(True)

    def _lock(self):
        while not self._spi.try_lock():
            pass
        self._spi.configure(baudrate=self._baudrate, polarity=0, phase=0)

    def _spi_write(self, data):
        self._lock()
        try:
            self._cs.set(False)
            self._spi.write(data)
            self._cs.set(True)
        finally:
            self._spi.unlock()

    def _spi_read(self, length):
        buf = bytearray(length)
        self._lock()
        try:
            self._cs.set(False)
            self._spi.readinto(buf)
            self._cs.set(True)
        finally:
            self._spi.unlock()
        return bytes(buf)
