class SiPoTransport:
    """SiPo (serial-in/parallel-out shift register) transport for CircuitPython.

    Drives cascadable SIPO shift registers (TPIC6B595, SN74HC595, etc.) whose
    SER IN/SRCK pins are electrically an SPI MOSI/SCK pair. Accepts a
    busio.SPI (hardware) or bitbangio.SPI (software) instance — both expose
    the same interface, so this class does not branch on which one it was
    given; the caller decides by constructing one or the other. RCK — and, if
    configured, SRCLR/G — are always plain digitalio.DigitalInOut outputs,
    independent of which SPI mode is used.

    Write-only: there is no read() or write_read().

    Args:
        spi: Configured busio.SPI or bitbangio.SPI instance.
        rck: digitalio.DigitalInOut configured as Direction.OUTPUT (register clock).
        srclr: digitalio.DigitalInOut configured as Direction.OUTPUT for SRCLR;
            None (default) disables it.
        g: digitalio.DigitalInOut configured as Direction.OUTPUT for G (output
            enable); None (default) disables it.
        baudrate: SPI clock frequency in Hz; default 1 000 000.
    """

    def __init__(self, spi, rck, srclr=None, g=None, baudrate=1_000_000):
        self._spi = spi
        self._rck = rck
        self._srclr = srclr
        self._g = g
        self._baudrate = baudrate
        self._rck.value = False
        if self._srclr is not None:
            self._srclr.value = True
        if self._g is not None:
            self._g.value = False

    def write(self, data):
        """Shift data out MSB-first, then latch it into the output register.

        Locks the bus, configures it for mode 0 at the configured baudrate,
        transfers data, unlocks, then pulses RCK HIGH then LOW to latch the
        shifted data into the storage register that drives the outputs.

        Args:
            data: Bytes to shift out, one byte per cascaded device.
        """
        while not self._spi.try_lock():
            pass
        try:
            self._spi.configure(baudrate=self._baudrate, polarity=0, phase=0)
            self._spi.write(bytes(data))
        finally:
            self._spi.unlock()
        self._rck.value = True
        self._rck.value = False

    def clear(self):
        """Pulse SRCLR LOW then HIGH to clear the shift register.

        The storage register (and therefore the outputs) is unaffected until
        the next write().

        Raises:
            RuntimeError: If srclr was not configured.
        """
        if self._srclr is None:
            raise RuntimeError('SRCLR not configured')
        self._srclr.value = False
        self._srclr.value = True

    def set_output_enable(self, enabled):
        """Drive G LOW (enabled) or HIGH (disabled).

        Args:
            enabled: True drives G LOW, letting the storage register drive the
                outputs. False drives G HIGH, forcing every output off without
                disturbing the storage register's contents.

        Raises:
            RuntimeError: If g was not configured.
        """
        if self._g is None:
            raise RuntimeError('G not configured')
        self._g.value = not enabled

    def close(self):
        """Deinit RCK and any configured SRCLR/G pins. Does not deinit spi."""
        self._rck.deinit()
        if self._srclr is not None:
            self._srclr.deinit()
        if self._g is not None:
            self._g.deinit()
