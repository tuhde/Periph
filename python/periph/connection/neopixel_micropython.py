from .base import Connection


def _encode(data):
    out = bytearray(len(data) * 3 + 16)
    for i, byte in enumerate(data):
        bits = 0
        for bit in range(7, -1, -1):
            bits = (bits << 3) | (0b110 if (byte >> bit) & 1 else 0b100)
        out[i * 3:(i + 1) * 3] = bits.to_bytes(3, "big")
    return bytes(out)


class NeoPixelConnection(Connection):
    """NeoPixel connection for MicroPython (wraps machine.SPI or machine.SoftSPI).

    Encodes pixel data as WS2812B-compatible SPI bitstream at 2.4 MHz.
    The SPI instance must be pre-configured at 2.4 MHz, mode 0, MSB-first
    before being passed to the connection. Write-only: there is no read()
    or write_read().

    Args:
        spi: Configured machine.SPI or machine.SoftSPI instance.
        en_pin: Optional OutputPin for hardware enable/power control.
    """

    def __init__(self, spi, en_pin=None):
        super().__init__(en_pin=en_pin)
        self._spi = spi

    def _write(self, data):
        """Encode and transmit pixel data, then hold MOSI low for reset.

        Args:
            data: Bytes to send (3 bytes per RGB pixel, 4 bytes per RGBW pixel).
        """
        encoded = _encode(data)
        self._spi.write(encoded)
