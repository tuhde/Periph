import spidev

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
    def __init__(self, bus_num, device_num):
        self._spi = spidev.SpiDev()
        self._spi.open(bus_num, device_num)
        self._spi.mode = 0
        self._spi.max_speed_hz = 2_400_000

    def write(self, data):
        encoded = _encode(data)
        self._spi.xfer2(list(encoded))

    def close(self):
        self._spi.close()