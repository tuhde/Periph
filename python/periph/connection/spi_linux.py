import spidev

from .register_connection import RegisterConnection


class SPIConnection(RegisterConnection):
    """SPI connection for Linux (wraps spidev, uses /dev/spidevBUS.DEVICE).

    Call close() to release the device when done.

    Args:
        bus_num: SPI bus number (opens /dev/spidevBUS.DEVICE).
        device_num: Chip-select line on the bus.
        mode: SPI mode 0–3 (CPOL/CPHA); default 0.
        max_speed_hz: Clock frequency in Hz; default 1 000 000.
        read_bit: Bit ORed into the command byte for a read; 0 if the chip
            has no such bit. Default 0x80.
        multi_byte_bit: Bit ORed in for multi-byte (burst) transfers when
            length > 1; None if the chip has no such bit and always
            auto-increments. Default None.
        int_pin: Optional InputPin for INT-line delivery.
        en_pin: Optional OutputPin for hardware enable/power control.
        en_active_high: True if the EN pin is active-high (default); False for active-low.
    """

    def __init__(self, bus_num, device_num, mode=0, max_speed_hz=1_000_000,
                 read_bit=0x80, multi_byte_bit=None, int_pin=None, en_pin=None, en_active_high=True):
        super().__init__(int_pin, en_pin, en_active_high=en_active_high)
        self._spi = spidev.SpiDev()
        self._spi.open(bus_num, device_num)
        self._spi.mode = mode
        self._spi.max_speed_hz = max_speed_hz
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
        cmd = (reg & ~self._read_bit) | (self._multi_byte_bit if len(payload) > 1 and self._multi_byte_bit else 0)
        self.write(bytes([cmd]) + payload)

    def _write(self, data):
        """Send bytes to the device.

        Args:
            data: Bytes to send.
        """
        self._spi.writebytes(list(data))

    def _read(self, n):
        """Read bytes from the device.

        Args:
            n: Number of bytes to read.

        Returns:
            bytes: Data received from the device.
        """
        return bytes(self._spi.readbytes(n))

    def _write_read(self, data, n):
        """Full-duplex write+read using xfer2 (CS held for the entire transfer).

        Sends len(data)+n bytes total and discards the first len(data) received
        bytes (the chip's response during the command phase).

        Args:
            data: Command bytes to send.
            n: Number of response bytes expected after the command.

        Returns:
            bytes: The n response bytes.
        """
        payload = list(data) + [0] * n
        result = self._spi.xfer2(payload)
        return bytes(result[len(data):])

    def close(self):
        """Release the spidev device."""
        self._spi.close()
