import spidev

from .base import Transport


class SPITransport(Transport):
    """SPI transport for Linux (wraps spidev, uses /dev/spidevBUS.DEVICE).

    Call close() to release the device when done.

    Args:
        bus_num: SPI bus number (opens /dev/spidevBUS.DEVICE).
        device_num: Chip-select line on the bus.
        mode: SPI mode 0–3 (CPOL/CPHA); default 0.
        max_speed_hz: Clock frequency in Hz; default 1 000 000.
    """

    def __init__(self, bus_num, device_num, mode=0, max_speed_hz=1_000_000):
        self._spi = spidev.SpiDev()
        self._spi.open(bus_num, device_num)
        self._spi.mode = mode
        self._spi.max_speed_hz = max_speed_hz

    def write(self, data):
        """Send bytes to the device.

        Args:
            data: Bytes to send.
        """
        self._spi.writebytes(list(data))

    def read(self, n):
        """Read bytes from the device.

        Args:
            n: Number of bytes to read.

        Returns:
            bytes: Data received from the device.
        """
        return bytes(self._spi.readbytes(n))

    def write_read(self, data, n):
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
