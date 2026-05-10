import spidev

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
    """NeoPixel transport using spidev.

    Drives WS2812B-compatible addressable LEDs over a single data line using
    timing-encoded NZR protocol via SPI MOSI at 800 Kbps.

    The caller is responsible for color ordering and bytes-per-pixel.
    """

    def __init__(self, bus_num, device_num):
        """Construct a NeoPixel transport.

        Args:
            bus_num: SPI bus number (e.g. 0 for /dev/spidev0.0).
            device_num: SPI device number (e.g. 0 for /dev/spidev0.0).
        """
        self._spi = spidev.SpiDev()
        self._spi.open(bus_num, device_num)
        self._spi.mode = 0
        self._spi.max_speed_hz = 2_400_000

    def write(self, data):
        """Encode and transmit NeoPixel data.

        Args:
            data: bytes of pixel data (3 bytes/pixel for RGB, 4 for RGBW).
        """
        encoded = _encode(data)
        self._spi.xfer2(list(encoded))

    def close(self):
        """Release the SPI device."""
        self._spi.close()