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


class INA219Full(INA219Minimal):
    PGA_1  = 0
    PGA_2  = 1
    PGA_4  = 2
    PGA_8  = 3

    BRNG_16V = 0
    BRNG_32V = 1

    ADC_9BIT      = 0x00
    ADC_10BIT     = 0x01
    ADC_11BIT     = 0x02
    ADC_12BIT     = 0x03
    ADC_AVG_2     = 0x09
    ADC_AVG_4     = 0x0A
    ADC_AVG_8     = 0x0B
    ADC_AVG_16    = 0x0C
    ADC_AVG_32    = 0x0D
    ADC_AVG_64    = 0x0E
    ADC_AVG_128   = 0x0F

    MODE_POWERDOWN       = 0
    MODE_SHUNT_TRIG      = 1
    MODE_BUS_TRIG        = 2
    MODE_SHUNT_BUS_TRIG  = 3
    MODE_ADC_OFF         = 4
    MODE_SHUNT_CONT      = 5
    MODE_BUS_CONT        = 6
    MODE_SHUNT_BUS_CONT  = 7

    def __init__(self, transport, r_shunt=0.1, max_current=2.0):
        super().__init__(transport, r_shunt, max_current)
        self._mode = self.MODE_SHUNT_BUS_CONT
        self._config = None

    def configure(self, brng=1, pga=3, badc=0x03, sadc=0x03, mode=7):
        config = ((brng & 0x01) << 13) | ((pga & 0x03) << 11) | ((badc & 0x0F) << 7) | ((sadc & 0x0F) << 3) | (mode & 0x07)
        self._mode = mode & 0x07
        self._config = config
        self._write_reg(self._REG_CONFIG, config)
        self._write_reg(self._REG_CAL, self._cal)

    def conversion_ready(self):
        return bool(self._read_reg(self._REG_BUS) & 0x0002)

    def overflow(self):
        return bool(self._read_reg(self._REG_BUS) & 0x0001)

    def reset(self):
        self._write_reg(self._REG_CONFIG, 0x8000)
        if self._config is not None:
            self._write_reg(self._REG_CONFIG, self._config)
        self._write_reg(self._REG_CAL, self._cal)

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
