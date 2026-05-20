from .base import Transport


def _encode(data):
    out = bytearray(len(data) * 3 + 16)
    for i, byte in enumerate(data):
        bits = 0
        for bit in range(7, -1, -1):
            bits = (bits << 3) | (0b110 if (byte >> bit) & 1 else 0b100)
        out[i * 3:(i + 1) * 3] = bits.to_bytes(3, "big")
    return bytes(out)


class NeoPixelTransport(Transport):
    """NeoPixel transport for CircuitPython (wraps busio.SPI).

    Encodes pixel data as WS2812B-compatible SPI bitstream at 2.4 MHz.
    Acquires and releases the bus lock around each transfer. No CS pin
    is needed — the strip latches on the reset low pulse.

    Args:
        spi: Configured busio.SPI instance.
    """

    def __init__(self, spi):
        self._spi = spi

    def write(self, data):
        """Encode and transmit pixel data, then hold MOSI low for reset.

        Args:
            data: Bytes to send (3 bytes per RGB pixel, 4 bytes per RGBW pixel).
        """
        encoded = _encode(data)
        while not self._spi.try_lock():
            pass
        try:
            self._spi.configure(baudrate=2_400_000, polarity=0, phase=0)
            self._spi.write(encoded)
        finally:
            self._spi.unlock()