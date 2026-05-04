from .base import Transport


class I2CTransport(Transport):
    def __init__(self, bus, addr):
        self._bus = bus
        self._addr = addr

    def write(self, data):
        while not self._bus.try_lock():
            pass
        try:
            self._bus.writeto(self._addr, bytes(data))
        finally:
            self._bus.unlock()

    def read(self, n):
        buf = bytearray(n)
        while not self._bus.try_lock():
            pass
        try:
            self._bus.readfrom_into(self._addr, buf)
        finally:
            self._bus.unlock()
        return bytes(buf)

    def write_read(self, data, n):
        buf = bytearray(n)
        while not self._bus.try_lock():
            pass
        try:
            self._bus.writeto_then_readfrom(self._addr, bytes(data), buf)
        finally:
            self._bus.unlock()
        return bytes(buf)
