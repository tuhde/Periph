from .base import Transport


def _encode(data):
    """Encode NeoPixel data bytes to SPI bitstream.

    Each NeoPixel bit is encoded as 3 SPI bits:
    - 0: 0b100 (417ns high, 833ns low)
    - 1: 0b110 (833ns high, 417ns low)
    """
    out = bytearray(len(data) * 3 + 16)
    for i, byte in enumerate(data):
        bits = 0
        for bit in range(7, -1, -1):
            bits = (bits << 3) | (0b110 if (byte >> bit) & 1 else 0b100)
        out[i * 3:(i + 1) * 3] = bits.to_bytes(3, 'big')
    return bytes(out)


class NeoPixelTransport(Transport):
    """NeoPixel transport using SPI bit-banging.

    Drives WS2812B-compatible addressable LEDs over a single data line using
    timing-encoded NZR protocol via SPI MOSI at 800 Kbps.

    The caller is responsible for color ordering and bytes-per-pixel.
    """

    def __init__(self, spi):
        """Construct a NeoPixel transport.

        Args:
            spi: busio.SPI instance.
        """
        self._spi = spi

    def write(self, data):
        """Encode and transmit NeoPixel data.

        Args:
            data: bytes of pixel data (3 bytes/pixel for RGB, 4 for RGBW).
        """
        while not self._spi.try_lock():
            pass
        try:
            self._spi.configure(baudrate=2_400_000, polarity=0, phase=0)
            encoded = _encode(data)
            self._spi.write(encoded)
        finally:
            self._spi.unlock()