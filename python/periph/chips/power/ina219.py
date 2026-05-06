import struct


class INA219Minimal:
    _REG_CONFIG = 0x00
    _REG_SHUNT = 0x01
    _REG_BUS = 0x02
    _REG_POWER = 0x03
    _REG_CURRENT = 0x04
    _REG_CAL = 0x05

    _CONFIG_DEFAULT = 0x399F

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
        return self._read_reg_signed(self._REG_SHUNT) * 1e-5

    def current(self):
        return self._read_reg_signed(self._REG_CURRENT) * self._current_lsb

    def power(self):
        return self._read_reg(self._REG_POWER) * 20 * self._current_lsb


class INA219Full(INA219Minimal):
    PGA_1 = 0
    PGA_2 = 1
    PGA_4 = 2
    PGA_8 = 3

    BRNG_16V = 0
    BRNG_32V = 1

    ADC_9BIT = 0
    ADC_10BIT = 1
    ADC_11BIT = 2
    ADC_12BIT = 3
    ADC_AVG_2 = 8
    ADC_AVG_4 = 9
    ADC_AVG_8 = 10
    ADC_AVG_16 = 11
    ADC_AVG_32 = 12
    ADC_AVG_64 = 13
    ADC_AVG_128 = 14

    MODE_POWERDOWN = 0
    MODE_SHUNT_TRIG = 1
    MODE_BUS_TRIG = 2
    MODE_SHUNT_BUS_TRIG = 3
    MODE_ADC_OFF = 4
    MODE_SHUNT_CONT = 5
    MODE_BUS_CONT = 6
    MODE_SHUNT_BUS_CONT = 7

    def __init__(self, transport, r_shunt=0.1, max_current=2.0):
        super().__init__(transport, r_shunt, max_current)
        self._saved_config = self._CONFIG_DEFAULT
        self._mode = self.MODE_SHUNT_BUS_CONT

    def configure(self, brng=1, pga=3, badc=3, sadc=3, mode=7):
        config = (0x01 if brng else 0) | (pga & 3) << 11 | ((badc & 0x0F) << 7) | (
            (sadc & 0x0F) << 3) | (mode & 0x07)
        self._saved_config = config
        self._mode = mode & 0x07
        self._write_reg(self._REG_CONFIG, config)
        self._write_reg(self._REG_CAL, self._cal)

    def conversion_ready(self):
        return bool(self._read_reg(self._REG_BUS) & 0x0002)

    def overflow(self):
        return bool(self._read_reg(self._REG_BUS) & 0x0001)

    def reset(self):
        self._write_reg(self._REG_CONFIG, 0x8000)
        self._write_reg(self._REG_CAL, self._cal)
        self._write_reg(self._REG_CONFIG, self._saved_config)

    def shutdown(self):
        config = self._read_reg(self._REG_CONFIG)
        self._mode = config & 0x07
        self._write_reg(self._REG_CONFIG, config & 0xFFF8)

    def wake(self):
        config = self._read_reg(self._REG_CONFIG)
        self._write_reg(self._REG_CONFIG, (config & 0xFFF8) | self._mode)

    def trigger(self):
        config = self._read_reg(self._REG_CONFIG)
        self._write_reg(self._REG_CONFIG, config)