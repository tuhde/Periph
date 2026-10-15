from .display_base import DisplayConnectionBase, WireMode


class DisplayConnection(DisplayConnectionBase):
    """Display connection (SPI + D/C#, RES#, BUSY) for MicroPython.

    Wraps a machine.SPI or machine.SoftSPI instance plus the CS#, D/C#, RES#
    and BUSY lines. CS# is driven manually: it idles high and is asserted low
    for each SPI transfer.

    Wire modes: SIMPLEX needs only a write-capable SPI. HALF_DUPLEX_PIN needs an
    SPI whose MISO is wired to the controller's DIN/DOUT line (MOSI joined to it
    through a series resistor); the connection reads with spi.read(). The
    controller's hardware half-duplex mode is not available through machine.SPI,
    so HALF_DUPLEX_HW raises ValueError.

    Args:
        spi: Configured machine.SPI or machine.SoftSPI (mode 0, MSB-first).
        cs: OutputPin for CS# (active low).
        dc: OutputPin for D/C# (low = command, high = data).
        res: OutputPin for RES# (active low), or None if not wired.
        busy: InputPin for BUSY, read with is_high(), or None if the controller has no BUSY line.
        wire_mode: A WireMode value (default SIMPLEX).
        reset_pulse_us: RES# low time in microseconds (default 200).
        reset_settle_ms: Wait after RES# is released in milliseconds (default 10).
        en_pin: Optional OutputPin for hardware enable/power control.
        en_active_high: True if the EN pin is active-high (default); False for active-low.
    """

    def __init__(self, spi, cs, dc, res, busy, wire_mode=WireMode.SIMPLEX, reset_pulse_us=200,
                 reset_settle_ms=10, en_pin=None, en_active_high=True):
        if wire_mode == WireMode.HALF_DUPLEX_HW:
            raise ValueError('HALF_DUPLEX_HW is not available on MicroPython; use HALF_DUPLEX_PIN')
        if wire_mode == WireMode.HALF_DUPLEX_PIN and not hasattr(spi, 'read'):
            raise ValueError('HALF_DUPLEX_PIN needs an SPI object with read() and MISO wired')
        super().__init__(dc, res, busy, wire_mode, reset_pulse_us, reset_settle_ms,
                         en_pin, en_active_high)
        self._spi = spi
        self._cs = cs
        self._cs.set(True)

    def _spi_write(self, data):
        self._cs.set(False)
        self._spi.write(data)
        self._cs.set(True)

    def _spi_read(self, length):
        self._cs.set(False)
        result = self._spi.read(length)
        self._cs.set(True)
        return bytes(result)
