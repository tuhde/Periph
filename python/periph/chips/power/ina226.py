import struct


class INA226Minimal:
    """INA226 36V, 16-bit current/voltage/power monitor — minimal interface.

    Provides bus voltage, shunt voltage, current, and power readings with no
    configuration beyond the transport and shunt resistor. Writes the
    Calibration Register automatically at construction.

    Default configuration (baked in at construction):
        - MODE = 7: shunt + bus, continuous
        - VBUSCT = 4: 1.1 ms bus voltage conversion time
        - VSHCT = 4: 1.1 ms shunt voltage conversion time
        - AVG = 0: 1 sample (no averaging)

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
        """Read bus voltage.

        Returns:
            float: Bus voltage in volts (raw × 1.25 mV LSB).
        """
        return self._read_reg(self._REG_BUS) * 1.25e-3

    def shunt_voltage(self):
        """Read differential shunt voltage.

        Returns:
            float: Shunt voltage in volts, signed (raw × 2.5 µV LSB).
        """
        return self._read_reg_signed(self._REG_SHUNT) * 2.5e-6

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
            float: Power in watts (raw × 25 × current LSB).
        """
        return self._read_reg(self._REG_POWER) * 25 * self._current_lsb


class INA226Full(INA226Minimal):
    """INA226 full interface — extends INA226Minimal with configuration and alert support.

    Adds Configuration Register programming, conversion-ready and overflow status,
    alert configuration, reset, and shutdown/wake.

    Alert function constants (pass to set_alert):
        SOL  — shunt voltage over-limit
        SUL  — shunt voltage under-limit
        BOL  — bus voltage over-limit
        BUL  — bus voltage under-limit
        POL  — power over-limit
        CNVR — conversion ready

    Args:
        transport: Configured I²C or SMBus transport pointing at the device.
        r_shunt: Shunt resistor value in ohms (default 0.1).
        max_current: Maximum expected current in amperes (default 2.0).
    """

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
        """Write the Configuration Register.

        Args:
            avg: Averaging count selector 0–7 (0 = 1 sample … 7 = 1024 samples).
            vbus_ct: Bus voltage conversion time selector 0–7 (default 4 = 1.1 ms).
            vsh_ct: Shunt voltage conversion time selector 0–7 (default 4 = 1.1 ms).
            mode: Operating mode 0–7 (7 = shunt+bus continuous).
        """
        config = ((avg & 0x07) << 9) | ((vbus_ct & 0x07) << 6) | ((vsh_ct & 0x07) << 3) | (mode & 0x07)
        self._mode = mode & 0x07
        self._write_reg(self._REG_CONFIG, config)

    def conversion_ready(self):
        """Read the Conversion Ready Flag (CVRF) from the Mask/Enable Register.

        Note:
            Reading Mask/Enable clears CVRF. Read it last if also checking other flags.

        Returns:
            bool: True if a conversion completed since the last Mask/Enable read.
        """
        return bool(self._read_reg(self._REG_MASK) & 0x0008)

    def overflow(self):
        """Read the Math Overflow Flag (OVF) from the Mask/Enable Register.

        Returns:
            bool: True if an arithmetic overflow occurred in the power calculation.
        """
        return bool(self._read_reg(self._REG_MASK) & 0x0004)

    def set_alert(self, function, limit=0, polarity=0, latch=0):
        """Configure the alert pin function and threshold.

        Only one alert function can be active at a time.

        Args:
            function: Alert function bitmask — one of SOL, SUL, BOL, BUL, POL, CNVR.
            limit: Threshold in natural units — volts for SOL/SUL/BOL/BUL, watts for POL,
                ignored for CNVR (default 0).
            polarity: Alert pin polarity — 0 = active-low (default), 1 = active-high.
            latch: Latch mode — 0 = transparent (default), 1 = latch until Mask/Enable is read.
        """
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
        """Read the Mask/Enable Register.

        Returns:
            int: Raw 16-bit Mask/Enable register value containing alert and status flags.
        """
        return self._read_reg(self._REG_MASK)

    def reset(self):
        """Reset all registers to power-on defaults, then re-write the Calibration Register."""
        self._write_reg(self._REG_CONFIG, 0x8000)
        self._write_reg(self._REG_CAL, self._cal)

    def shutdown(self):
        """Enter power-down mode (MODE = 000) and save the current mode for wake()."""
        config = self._read_reg(self._REG_CONFIG)
        self._mode = config & 0x07
        self._write_reg(self._REG_CONFIG, config & 0xFFF8)

    def wake(self):
        """Restore the operating mode saved by shutdown()."""
        config = self._read_reg(self._REG_CONFIG)
        self._write_reg(self._REG_CONFIG, (config & 0xFFF8) | self._mode)

    def manufacturer_id(self):
        """Read the Manufacturer ID register.

        Returns:
            int: Manufacturer ID; expect 0x5449 (Texas Instruments).
        """
        return self._read_reg(self._REG_MFR_ID)

    def die_id(self):
        """Read the Die ID register.

        Returns:
            int: Die revision ID; expect 0x2260.
        """
        return self._read_reg(self._REG_DIE_ID)
