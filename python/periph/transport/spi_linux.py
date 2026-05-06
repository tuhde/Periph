import spidev

from .base import Transport


class SPITransport(Transport):
    def __init__(self, bus_num, device_num, mode=0, max_speed_hz=1_000_000):
        self._spi = spidev.SpiDev()
        self._spi.open(bus_num, device_num)
        self._spi.mode = mode
        self._spi.max_speed_hz = max_speed_hz

    def write(self, data):
        self._spi.writebytes(list(data))

    def read(self, n):
        return bytes(self._spi.readbytes(n))

    def write_read(self, data, n):
        payload = list(data) + [0] * n
        result = self._spi.xfer2(payload)
        return bytes(result[len(data):])

    def close(self):
        self._spi.close()
