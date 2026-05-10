from .base import Transport


def _encode(data):
    out = bytearray(len(data) * 3 + 16)
    for i, byte in enumerate(data):
        bits = 0
        for bit in range(7, -1, -1):
            bits = (bits << 3) | (0b110 if (byte >> bit) & 1 else 0b100)
        out[i * 3:(i + 1) * 3] = bits.to_bytes(3, 'big')
    return bytes(out)


class NeoPixelTransport(Transport):
    def __init__(self, spi):
        self._spi = spi

    def write(self, data):
        encoded = _encode(data)
        self._spi.write(encoded)