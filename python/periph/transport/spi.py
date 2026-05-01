from .base import Transport


class SPITransport(Transport):
    def __init__(self, bus, cs):
        self._bus = bus
        self._cs = cs
        self._cs.value(1)

    def write(self, data):
        self._cs.value(0)
        self._bus.write(data)
        self._cs.value(1)

    def read(self, n):
        self._cs.value(0)
        result = self._bus.read(n)
        self._cs.value(1)
        return result

    def write_read(self, data, n):
        buf = bytearray(n)
        self._cs.value(0)
        self._bus.write(data)
        self._bus.readinto(buf)
        self._cs.value(1)
        return bytes(buf)
