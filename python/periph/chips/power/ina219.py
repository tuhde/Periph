import struct


class INA219Minimal:
    _REG_CONFIG  = 0x00
    _REG_SHUNT   = 0x01
    _REG_BUS     = 0x02
    _REG_POWER   = 0x03
    _REG_CURRENT = 0x04
    _REG_CAL     = 0x05

    def __init__(self, transport, r_shunt=0.1, max_current=2.0):
        self._transport = transport
        self._current_lsb = max_current / 32768
        self._cal = int(0.04096 / (self._current_lsb * r_shunt)) & 0xFFFE
        self._write_reg(self._REG_CAL, self._cal)

    def _write_reg(self, reg, value):
        self._transport.write(struct.pack('>BH', reg, value))

    def _read_reg(self, reg):
        return struct.unpack('>H', self._transport.write_read(bytes([reg]), 2))[0]

    def _read_reg_signed(self, reg):
        return struct.unpack('>h', self._transport.write_read(bytes([reg]), 2))[0]

    def voltage(self):
        return (self._read_reg(self._REG_BUS) >> 3) * 4e-3

    def shunt_voltage(self):
        return self._read_reg_signed(self._REG_SHUNT) * 10e-6

    def current(self):
        return self._read_reg_signed(self._REG_CURRENT) * self._current_lsb

    def power(self):
        return self._read_reg(self._REG_POWER) * 20 * self._current_lsb
