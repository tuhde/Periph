from .base import Transport


class SPITransport(Transport):
    def __init__(self, bus, cs, baudrate=1_000_000, polarity=0, phase=0):
        self._bus = bus
        self._cs = cs
        self._baudrate = baudrate
        self._polarity = polarity
        self._phase = phase
        self._cs.value = True

    def write(self, data):
        while not self._bus.try_lock():
            pass
        try:
            self._bus.configure(baudrate=self._baudrate, polarity=self._polarity, phase=self._phase)
            self._cs.value = False
            self._bus.write(bytes(data))
        finally:
            self._cs.value = True
            self._bus.unlock()

    def read(self, n):
        buf = bytearray(n)
        while not self._bus.try_lock():
            pass
        try:
            self._bus.configure(baudrate=self._baudrate, polarity=self._polarity, phase=self._phase)
            self._cs.value = False
            self._bus.readinto(buf)
        finally:
            self._cs.value = True
            self._bus.unlock()
        return bytes(buf)

    def write_read(self, data, n):
        data = bytes(data)
        out_buf = data + bytes(n)
        in_buf = bytearray(len(out_buf))
        while not self._bus.try_lock():
            pass
        try:
            self._bus.configure(baudrate=self._baudrate, polarity=self._polarity, phase=self._phase)
            self._cs.value = False
            self._bus.write_readinto(out_buf, in_buf)
        finally:
            self._cs.value = True
            self._bus.unlock()
        return bytes(in_buf[len(data):])
