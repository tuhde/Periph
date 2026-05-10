from .base import Transport


class SPITransport(Transport):
    """SPI transport for CircuitPython (wraps busio.SPI).

    Acquires and releases the bus lock around every operation. CS is a
    digitalio.DigitalInOut driven manually (False = asserted, True = deasserted).

    Args:
        bus: Configured busio.SPI instance.
        cs: digitalio.DigitalInOut for chip select (active low).
        baudrate: Clock frequency in Hz (default 1 000 000).
        polarity: CPOL — 0 or 1 (default 0).
        phase: CPHA — 0 or 1 (default 0).
    """

    def __init__(self, bus, cs, baudrate=1_000_000, polarity=0, phase=0):
        self._bus = bus
        self._cs = cs
        self._baudrate = baudrate
        self._polarity = polarity
        self._phase = phase
        self._cs.value = True

    def write(self, data):
        """Assert CS, send bytes, deassert CS.

        Acquires the bus lock and calls configure() before each transfer.

        Args:
            data: Bytes to send.
        """
        while not self._bus.try_lock():
            pass
        try:
            self._bus.configure(baudrate=self._baudrate, polarity=self._polarity, phase=self._phase)
            self._cs.value = False
            self._bus.write(bytes(data))
        finally:
            self._cs.value = True
            self._bus.unlock()

    def read(self, n):
        """Assert CS, read n bytes, deassert CS.

        Acquires the bus lock and calls configure() before each transfer.

        Args:
            n: Number of bytes to read.

        Returns:
            bytes: Data received from the device.
        """
        buf = bytearray(n)
        while not self._bus.try_lock():
            pass
        try:
            self._bus.configure(baudrate=self._baudrate, polarity=self._polarity, phase=self._phase)
            self._cs.value = False
            self._bus.readinto(buf)
        finally:
            self._cs.value = True
            self._bus.unlock()
        return bytes(buf)

    def write_read(self, data, n):
        """Assert CS, perform full-duplex write+read, deassert CS.

        Builds a combined output buffer (command bytes + n zero bytes) and a
        same-length input buffer, calls write_readinto, then returns the trailing
        n bytes (the chip's response after the command phase).

        Args:
            data: Command bytes to send.
            n: Number of response bytes expected.

        Returns:
            bytes: The n response bytes captured after the command phase.
        """
        data = bytes(data)
        out_buf = data + bytes(n)
        in_buf = bytearray(len(out_buf))
        while not self._bus.try_lock():
            pass
        try:
            self._bus.configure(baudrate=self._baudrate, polarity=self._polarity, phase=self._phase)
            self._cs.value = False
            self._bus.write_readinto(out_buf, in_buf)
        finally:
            self._cs.value = True
            self._bus.unlock()
        return bytes(in_buf[len(data):])
