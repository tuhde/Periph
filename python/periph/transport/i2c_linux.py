from smbus2 import SMBus, i2c_msg

from .base import Transport


class I2CTransport(Transport):
    def __init__(self, bus, addr):
        if isinstance(bus, int):
            self._bus = SMBus(bus)
            self._owns_bus = True
        else:
            self._bus = bus
            self._owns_bus = False
        self._addr = addr

    def write(self, data):
        self._bus.i2c_rdwr(i2c_msg.write(self._addr, list(data)))

    def read(self, n):
        msg = i2c_msg.read(self._addr, n)
        self._bus.i2c_rdwr(msg)
        return bytes(msg)

    def write_read(self, data, n):
        write_msg = i2c_msg.write(self._addr, list(data))
        read_msg = i2c_msg.read(self._addr, n)
        self._bus.i2c_rdwr(write_msg, read_msg)
        return bytes(read_msg)

    def close(self):
        if self._owns_bus:
            self._bus.close()
