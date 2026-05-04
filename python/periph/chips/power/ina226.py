import struct


class INA226Minimal:
    _REG_CONFIG  = 0x00
    _REG_SHUNT   = 0x01
    _REG_BUS     = 0x02
    _REG_POWER   = 0x03
    _REG_CURRENT = 0x04
    _REG_CAL     = 0x05

    # AVG=1, VBUSCT=1.1ms, VSHCT=1.1ms, MODE=shunt+bus continuous
    _CONFIG_DEFAULT = 0x4127

    def __init__(self, transport, r_shunt=0.1, max_current=2.0):
        self._transport = transport
        self._current_lsb = max_current / 32768
        self._cal = int(0.00512 / (self._current_lsb * r_shunt))
        self._write_reg(self._REG_CONFIG, self._CONFIG_DEFAULT)
        self._write_reg(self._REG_CAL, self._cal)

    def _write_reg(self, reg, value):
        self._transport.write(struct.pack('>BH', reg, value))

    def _read_reg(self, reg):
        return struct.unpack('>H', self._transport.write_read(bytes([reg]), 2))[0]

    def _read_reg_signed(self, reg):
        return struct.unpack('>h', self._transport.write_read(bytes([reg]), 2))[0]

    def voltage(self):
        return self._read_reg(self._REG_BUS) * 1.25e-3

    def shunt_voltage(self):
        return self._read_reg_signed(self._REG_SHUNT) * 2.5e-6

    def current(self):
        return self._read_reg_signed(self._REG_CURRENT) * self._current_lsb

    def power(self):
        return self._read_reg(self._REG_POWER) * 25 * self._current_lsb


class INA226Full(INA226Minimal):
    SOL  = 0x8000
    SUL  = 0x4000
    BOL  = 0x2000
    BUL  = 0x1000
    POL  = 0x0800
    CNVR = 0x0400
    AFF  = 0x0010

    _REG_MASK   = 0x06
    _REG_ALERT  = 0x07
    _REG_MFR_ID = 0xFE
    _REG_DIE_ID = 0xFF

    def __init__(self, transport, r_shunt=0.1, max_current=2.0):
        super().__init__(transport, r_shunt, max_current)
        self._mode = 0x07

    def configure(self, avg=0, vbus_ct=4, vsh_ct=4, mode=7):
        config = ((avg & 0x07) << 9) | ((vbus_ct & 0x07) << 6) | ((vsh_ct & 0x07) << 3) | (mode & 0x07)
        self._mode = mode & 0x07
        self._write_reg(self._REG_CONFIG, config)

    def conversion_ready(self):
        return bool(self._read_reg(self._REG_MASK) & 0x0008)

    def overflow(self):
        return bool(self._read_reg(self._REG_MASK) & 0x0004)

    def set_alert(self, function, limit=0, polarity=0, latch=0):
        if function in (self.SOL, self.SUL):
            raw = int(limit / 2.5e-6)
        elif function in (self.BOL, self.BUL):
            raw = int(limit / 1.25e-3)
        elif function == self.POL:
            raw = int(limit / (25 * self._current_lsb))
        else:
            raw = 0
        mask = function | ((polarity & 1) << 1) | (latch & 1)
        self._write_reg(self._REG_MASK, mask)
        self._write_reg(self._REG_ALERT, raw & 0xFFFF)

    def alert_flags(self):
        return self._read_reg(self._REG_MASK)

    def reset(self):
        self._write_reg(self._REG_CONFIG, 0x8000)
        self._write_reg(self._REG_CAL, self._cal)

    def shutdown(self):
        config = self._read_reg(self._REG_CONFIG)
        self._mode = config & 0x07
        self._write_reg(self._REG_CONFIG, config & 0xFFF8)

    def wake(self):
        config = self._read_reg(self._REG_CONFIG)
        self._write_reg(self._REG_CONFIG, (config & 0xFFF8) | self._mode)

    def manufacturer_id(self):
        return self._read_reg(self._REG_MFR_ID)

    def die_id(self):
        return self._read_reg(self._REG_DIE_ID)
