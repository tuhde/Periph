import struct


class INA219Minimal:
    """INA219 26V, 12-bit current/voltage/power monitor — minimal interface.

    Provides bus voltage, shunt voltage, current, and power readings with no
    configuration beyond the transport and shunt resistor. Writes the
    Calibration Register automatically at construction.

    Default chip configuration (power-on defaults, not rewritten):
        - BRNG = 1: 32 V bus full-scale range
        - PG = 11: PGA ÷8, ±320 mV shunt full-scale
        - BADC = 0011: 12-bit, 532 µs
        - SADC = 0011: 12-bit, 532 µs
        - MODE = 111: shunt + bus, continuous

    Args:
        transport: Configured I²C or SMBus transport pointing at the device.
        r_shunt: Shunt resistor value in ohms (default 0.1).
        max_current: Maximum expected current in amperes (default 2.0).
    """

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
        """Read bus voltage.

        Returns:
            float: Bus voltage in volts ((raw >> 3) × 4 mV LSB).
        """
        return (self._read_reg(self._REG_BUS) >> 3) * 4e-3

    def shunt_voltage(self):
        """Read differential shunt voltage.

        Returns:
            float: Shunt voltage in volts, signed (raw × 10 µV LSB).
        """
        return self._read_reg_signed(self._REG_SHUNT) * 10e-6

    def current(self):
        """Read calculated current through the shunt.

        Requires the Calibration Register to be programmed (done at construction).

        Returns:
            float: Current in amperes, signed.
        """
        return self._read_reg_signed(self._REG_CURRENT) * self._current_lsb

    def power(self):
        """Read calculated power.

        Requires the Calibration Register to be programmed (done at construction).

        Returns:
            float: Power in watts (raw × 20 × current LSB).
        """
        return self._read_reg(self._REG_POWER) * 20 * self._current_lsb


class INA219Full(INA219Minimal):
    """INA219 full interface — extends INA219Minimal with full configuration and power management.

    Adds Configuration Register programming (bus range, PGA, ADC resolution/averaging, mode),
    conversion-ready and overflow status, reset, and shutdown/wake.

    Args:
        transport: Configured I²C or SMBus transport pointing at the device.
        r_shunt: Shunt resistor value in ohms (default 0.1).
        max_current: Maximum expected current in amperes (default 2.0).
    """

    BRNG_16V = 0
    BRNG_32V = 1

    PGA_1  = 0
    PGA_2  = 1
    PGA_4  = 2
    PGA_8  = 3

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

    MODE_POWERDOWN        = 0
    MODE_SHUNT_TRIG      = 1
    MODE_BUS_TRIG        = 2
    MODE_SHUNT_BUS_TRIG  = 3
    MODE_ADC_OFF         = 4
    MODE_SHUNT_CONT      = 5
    MODE_BUS_CONT        = 6
    MODE_SHUNT_BUS_CONT  = 7

    def __init__(self, transport, r_shunt=0.1, max_current=2.0):
        super().__init__(transport, r_shunt, max_current)
        self._saved_mode = self.MODE_SHUNT_BUS_CONT

    def configure(self, brng=1, pga=3, badc=0x03, sadc=0x03, mode=7):
        """Write the Configuration Register.

        Args:
            brng: Bus voltage range — 0 = 16 V FSR, 1 = 32 V FSR (default 1).
            pga: Shunt PGA gain — 0 = ÷1, 1 = ÷2, 2 = ÷4, 3 = ÷8 (default 3).
            badc: Bus ADC resolution/averaging — 0x00–0x0F (default 0x03 = 12-bit).
            sadc: Shunt ADC resolution/averaging — 0x00–0x0F (default 0x03 = 12-bit).
            mode: Operating mode 0–7 (default 7 = shunt+bus continuous).
        """
        config = ((brng & 1) << 13) | ((pga & 3) << 11) | ((badc & 0x0F) << 7) | ((sadc & 0x0F) << 3) | (mode & 7)
        self._saved_mode = mode & 7
        self._write_reg(self._REG_CONFIG, config)
        self._write_reg(self._REG_CAL, self._cal)

    def conversion_ready(self):
        """Read the Conversion Ready Flag (CNVR) from the Bus Voltage register.

        Returns:
            bool: True if a conversion completed since the last read.
        """
        return bool(self._read_reg(self._REG_BUS) & 0x02)

    def overflow(self):
        """Read the Math Overflow Flag (OVF) from the Bus Voltage register.

        Returns:
            bool: True if an arithmetic overflow occurred in current/power calculation.
        """
        return bool(self._read_reg(self._REG_BUS) & 0x01)

    def reset(self):
        """Reset all registers to power-on defaults, then re-write the Calibration Register."""
        self._write_reg(self._REG_CONFIG, 0x8000)
        self._write_reg(self._REG_CAL, self._cal)

    def shutdown(self):
        """Enter power-down mode (MODE = 000) and save the current mode for wake()."""
        config = self._read_reg(self._REG_CONFIG)
        self._saved_mode = config & 7
        self._write_reg(self._REG_CONFIG, config & 0xFFF8)

    def wake(self):
        """Restore the operating mode saved by shutdown()."""
        config = self._read_reg(self._REG_CONFIG)
        self._write_reg(self._REG_CONFIG, (config & 0xFFF8) | self._saved_mode)

    def trigger(self):
        """Re-write the current mode to trigger a single-shot conversion.

        Only effective when the current mode is a triggered mode (1, 2, or 3).
        """
        config = self._read_reg(self._REG_CONFIG)
        self._write_reg(self._REG_CONFIG, config)
