from .register_connection import RegisterConnection


class SPIConnection(RegisterConnection):
    """SPI connection for MicroPython (wraps machine.SPI).

    CS is a machine.Pin driven manually; it idles high and is asserted low
    for the duration of each operation.

    Args:
        bus: Configured machine.SPI or machine.SoftSPI instance.
        cs: machine.Pin for chip select (active low).
        read_bit: Bit ORed into the command byte for a read; 0 if the chip
            has no such bit. Default 0x80.
        multi_byte_bit: Bit ORed in for multi-byte (burst) transfers when
            length > 1; None if the chip has no such bit and always
            auto-increments. Default None.
        int_pin: Optional InputPin for INT-line delivery.
        en_pin: Optional OutputPin for hardware enable/power control.
    """

    def __init__(self, bus, cs, read_bit=0x80, multi_byte_bit=None, int_pin=None, en_pin=None):
        super().__init__(int_pin, en_pin)
        self._bus = bus
        self._cs = cs
        self._cs.value(1)
        self._read_bit = read_bit
        self._multi_byte_bit = multi_byte_bit

    def read_reg(self, reg: int, length: int) -> bytes:
        """Read `length` bytes starting at register `reg`, building the SPI command byte.

        Args:
            reg: Register address.
            length: Number of bytes to read.

        Returns:
            bytes: Data received from the device.
        """
        cmd = reg | self._read_bit
        if length > 1 and self._multi_byte_bit:
            cmd |= self._multi_byte_bit
        return self.write_read(bytes([cmd]), length)

    def write_reg(self, reg: int, data) -> None:
        """Write `data` to register `reg`, building the SPI command byte.

        Args:
            reg: Register address.
            data: Bytes to write, or a single int for a 1-byte register.
        """
        payload = bytes([data]) if isinstance(data, int) else bytes(data)
        cmd = reg | (self._multi_byte_bit if len(payload) > 1 and self._multi_byte_bit else 0)
        self.write(bytes([cmd]) + payload)

    def _write(self, data):
        """Assert CS, send bytes, deassert CS.

        Args:
            data: Bytes to send.
        """
        self._cs.value(0)
        self._bus.write(data)
        self._cs.value(1)

    def _read(self, n):
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

    def _write_read(self, data, n):
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
