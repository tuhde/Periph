from .base import Transport


class SPITransport(Transport):
    """SPI transport for MicroPython (wraps machine.SPI).

    CS is a machine.Pin driven manually; it idles high and is asserted low
    for the duration of each operation.

    Args:
        bus: Configured machine.SPI or machine.SoftSPI instance.
        cs: machine.Pin for chip select (active low).
    """

    def __init__(self, bus, cs):
        self._bus = bus
        self._cs = cs
        self._cs.value(1)

    def write(self, data):
        """Assert CS, send bytes, deassert CS.

        Args:
            data: Bytes to send.
        """
        self._cs.value(0)
        self._bus.write(data)
        self._cs.value(1)

    def read(self, n):
        """Assert CS, clock out n bytes, capture response, deassert CS.

        Args:
            n: Number of bytes to read.

        Returns:
            bytes: Data received from the device.
        """
        self._cs.value(0)
        result = self._bus.read(n)
        self._cs.value(1)
        return result

    def write_read(self, data, n):
        """Assert CS, send command bytes, read n bytes, deassert CS.

        Write and read phases are separate SPI transfers within one CS assertion.

        Args:
            data: Command bytes to send.
            n: Number of response bytes to read.

        Returns:
            bytes: Data received during the read phase.
        """
        buf = bytearray(n)
        self._cs.value(0)
        self._bus.write(data)
        self._bus.readinto(buf)
        self._cs.value(1)
        return bytes(buf)
