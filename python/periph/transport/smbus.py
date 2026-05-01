from .base import Transport


def _crc8(data):
    crc = 0
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc << 1) ^ 0x07 if crc & 0x80 else crc << 1
        crc &= 0xFF
    return crc


class SMBusTransport(Transport):
    def __init__(self, bus, addr, pec=False):
        if not (0x08 <= addr <= 0x77):
            raise ValueError("SMBus address must be in range 0x08-0x77")
        self._bus = bus
        self._addr = addr
        self._pec = pec

    def write(self, data):
        if self._pec:
            data = bytes(data) + bytes([_crc8(bytes([self._addr << 1]) + bytes(data))])
        self._bus.writeto(self._addr, data)

    def read(self, n):
        raw = self._bus.readfrom(self._addr, n + 1 if self._pec else n)
        if self._pec:
            if _crc8(bytes([(self._addr << 1) | 1]) + raw[:-1]) != raw[-1]:
                raise OSError("SMBus PEC error")
            return bytes(raw[:-1])
        return bytes(raw)

    def write_read(self, data, n):
        buf = bytearray(n + 1 if self._pec else n)
        self._bus.writeto_then_readfrom(self._addr, bytes(data), buf)
        if self._pec:
            expected = _crc8(
                bytes([self._addr << 1]) + bytes(data) +
                bytes([(self._addr << 1) | 1]) + bytes(buf[:-1])
            )
            if expected != buf[-1]:
                raise OSError("SMBus PEC error")
            return bytes(buf[:-1])
        return bytes(buf)
