class SiPoTransport:
    """SiPo (serial-in/parallel-out shift register) transport for MicroPython.

    Drives cascadable SIPO shift registers (TPIC6B595, SN74HC595, etc.) whose
    SER IN/SRCK pins are electrically an SPI MOSI/SCK pair. Accepts a
    machine.SPI (hardware) or machine.SoftSPI (software) instance — both
    expose the same write() method, so this class does not branch on which
    one it was given; the caller decides by constructing one or the other.
    RCK — and, if configured, SRCLR/G — are always plain machine.Pin outputs,
    independent of which SPI mode is used.

    Write-only: there is no read() or write_read().

    Args:
        spi: Configured machine.SPI or machine.SoftSPI instance (mode 0, MSB-first).
        rck: machine.Pin configured as output (register clock).
        srclr: machine.Pin configured as output for SRCLR; None (default) disables it.
        g: machine.Pin configured as output for G (output enable); None (default) disables it.
    """

    def __init__(self, spi, rck, srclr=None, g=None):
        self._spi = spi
        self._rck = rck
        self._srclr = srclr
        self._g = g
        self._rck.value(0)
        if self._srclr is not None:
            self._srclr.value(1)
        if self._g is not None:
            self._g.value(0)

    def write(self, data):
        """Shift data out MSB-first, then latch it into the output register.

        Transfers data over the SPI/SoftSPI object, then pulses RCK HIGH then
        LOW to latch the shifted data into the storage register that drives
        the outputs.

        Args:
            data: Bytes to shift out, one byte per cascaded device.
        """
        self._spi.write(data)
        self._rck.value(1)
        self._rck.value(0)

    def clear(self):
        """Pulse SRCLR LOW then HIGH to clear the shift register.

        The storage register (and therefore the outputs) is unaffected until
        the next write().

        Raises:
            RuntimeError: If srclr was not configured.
        """
        if self._srclr is None:
            raise RuntimeError('SRCLR not configured')
        self._srclr.value(0)
        self._srclr.value(1)

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
        self._g.value(0 if enabled else 1)

    def close(self):
        """Release pins. No-op on MicroPython; provided for interface consistency."""
        pass
