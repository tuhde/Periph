from .base import Transport


class I2CTransport(Transport):
    def __init__(self, bus, addr):
        self._bus = bus
        self._addr = addr

    def write(self, data):
        self._bus.writeto(self._addr, data)

    def read(self, n):
        return self._bus.readfrom(self._addr, n)

    def write_read(self, data, n):
        buf = bytearray(n)
        self._bus.writeto_then_readfrom(self._addr, data, buf)
        return bytes(buf)
