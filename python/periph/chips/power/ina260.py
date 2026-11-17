import struct


class INA260Minimal:
    """INA260 36V, 16-bit current/voltage/power monitor with integrated shunt — minimal interface.

    Provides bus voltage, current, and power readings with no configuration
    beyond the connection. The shunt is integrated and the current/power
    registers have fixed LSBs, so no calibration is needed.

    Default configuration (chip power-on state, not rewritten):
        - MODE = 7: shunt current + bus voltage, continuous
        - VBUSCT = 4: 1.1 ms bus voltage conversion time
        - ISHCT = 4: 1.1 ms shunt current conversion time
        - AVG = 0: 1 sample (no averaging)

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
    """

    _REG_CONFIG  = 0x00
    _REG_CURRENT = 0x01
    _REG_BUS     = 0x02
    _REG_POWER   = 0x03

    def __init__(self, connection):
        self._connection = connection

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, struct.pack('>H', value))

    def _read_reg(self, reg):
        return struct.unpack('>H', self._connection.read_reg(reg, 2))[0]

    def _read_reg_signed(self, reg):
        return struct.unpack('>h', self._connection.read_reg(reg, 2))[0]

    def voltage(self):
        """Read bus voltage.

        Returns:
            float: Bus voltage in volts (raw × 1.25 mV LSB).
        """
        return self._read_reg(self._REG_BUS) * 1.25e-3

    def current(self):
        """Read current through the integrated shunt.

        Returns:
            float: Current in amperes, signed (raw × 1.25 mA LSB); positive flows IN+ → IN−.
        """
        return self._read_reg_signed(self._REG_CURRENT) * 1.25e-3

    def power(self):
        """Read calculated power.

        Returns:
            float: Power in watts (raw × 10 mW LSB), always positive.
        """
        return self._read_reg(self._REG_POWER) * 10e-3


class INA260Full(INA260Minimal):
    """INA260 full interface — extends INA260Minimal with configuration and alert support.

    Adds Configuration Register programming, triggered conversions,
    conversion-ready and overflow status, alert configuration, reset, and
    shutdown/wake.

    Alert function constants (pass to set_alert):
        OCL  — over-current limit
        UCL  — under-current limit
        BOL  — bus voltage over-limit
        BUL  — bus voltage under-limit
        POL  — power over-limit
        CNVR — conversion ready

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
    """

    AVG_1, AVG_4, AVG_16, AVG_64, AVG_128, AVG_256, AVG_512, AVG_1024 = range(8)
    (CT_140US, CT_204US, CT_332US, CT_588US,
     CT_1100US, CT_2116US, CT_4156US, CT_8244US) = range(8)

    MODE_POWER_DOWN = 0
    MODE_SHUNT_TRIGGERED = 1
    MODE_BUS_TRIGGERED = 2
    MODE_SHUNT_BUS_TRIGGERED = 3
    MODE_SHUNT_CONT = 5
    MODE_BUS_CONT = 6
    MODE_SHUNT_BUS_CONT = 7

    OCL  = 0x8000
    UCL  = 0x4000
    BOL  = 0x2000
    BUL  = 0x1000
    POL  = 0x0800
    CNVR = 0x0400
    AFF  = 0x0010

    _REG_MASK   = 0x06
    _REG_ALERT  = 0x07
    _REG_MFR_ID = 0xFE
    _REG_DIE_ID = 0xFF

    _CONFIG_BASE = 0x6000  # bits 14:12 must be written as 110

    def __init__(self, connection):
        super().__init__(connection)
        self._avg = 0
        self._vbus_ct = 4
        self._ish_ct = 4
        self._mode = 7

    def _write_config(self, mode):
        config = (self._CONFIG_BASE | (self._avg << 9) | (self._vbus_ct << 6)
                  | (self._ish_ct << 3) | (mode & 0x07))
        self._write_reg(self._REG_CONFIG, config)

    def configure(self, avg=0, vbus_ct=4, ish_ct=4, mode=7):
        """Write the Configuration Register (bits 14:12 kept at 110).

        Args:
            avg: Averaging count selector 0–7 (AVG_1 … AVG_1024).
            vbus_ct: Bus voltage conversion time selector 0–7 (default 4 = 1.1 ms).
            ish_ct: Shunt current conversion time selector 0–7 (default 4 = 1.1 ms).
            mode: Operating mode 0–7 (7 = shunt + bus continuous).
        """
        self._avg = avg & 0x07
        self._vbus_ct = vbus_ct & 0x07
        self._ish_ct = ish_ct & 0x07
        self._mode = mode & 0x07
        self._write_config(self._mode)

    def trigger(self, mode=MODE_SHUNT_BUS_TRIGGERED):
        """Start one triggered conversion with the current settings.

        Rewrites the Configuration Register. Poll conversion_ready() for completion.

        Args:
            mode: Triggered mode — MODE_SHUNT_TRIGGERED, MODE_BUS_TRIGGERED or
                MODE_SHUNT_BUS_TRIGGERED (default).
        """
        self._write_config(mode)

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

        Writes the Alert Limit register first, then Mask/Enable, so the new
        function never compares against a stale threshold. Only one alert
        function can be active at a time.

        Args:
            function: Alert function bitmask — one of OCL, UCL, BOL, BUL, POL, CNVR.
            limit: Threshold in natural units — amperes for OCL/UCL, volts for BOL/BUL,
                watts for POL, ignored for CNVR (limit register not written).
            polarity: Alert pin polarity — 0 = active-low (default), 1 = active-high.
            latch: Latch mode — 0 = transparent (default), 1 = latch until Mask/Enable is read.
        """
        if function in (self.OCL, self.UCL):
            raw = int(round(limit / 1.25e-3))
        elif function in (self.BOL, self.BUL):
            raw = int(round(limit / 1.25e-3))
        elif function == self.POL:
            raw = int(round(limit / 10e-3))
        else:
            raw = None
        if raw is not None:
            self._write_reg(self._REG_ALERT, raw & 0xFFFF)
        mask = function | ((polarity & 1) << 1) | (latch & 1)
        self._write_reg(self._REG_MASK, mask)

    def clear_alert(self):
        """Disable all alert functions (write Mask/Enable = 0)."""
        self._write_reg(self._REG_MASK, 0)

    def alert_flags(self):
        """Read the Mask/Enable Register.

        Note:
            Clears CVRF and, in latch mode, AFF.

        Returns:
            int: Raw 16-bit Mask/Enable register value containing alert and status flags.
        """
        return self._read_reg(self._REG_MASK)

    def reset(self):
        """Reset all registers to power-on defaults (RST bit)."""
        self._write_reg(self._REG_CONFIG, 0x8000)
        self._avg = 0
        self._vbus_ct = 4
        self._ish_ct = 4
        self._mode = 7

    def shutdown(self):
        """Enter power-down mode (MODE = 000); the previous mode is kept for wake()."""
        self._write_config(self.MODE_POWER_DOWN)

    def wake(self):
        """Restore the operating mode that was active before shutdown()."""
        self._write_config(self._mode)

    def manufacturer_id(self):
        """Read the Manufacturer ID register.

        Returns:
            int: Manufacturer ID; expect 0x5449 (Texas Instruments).
        """
        return self._read_reg(self._REG_MFR_ID)

    def die_id(self):
        """Read the Die ID register.

        Returns:
            int: Die ID; (value >> 4) is 0x227, the low nibble is the revision.
        """
        return self._read_reg(self._REG_DIE_ID)
