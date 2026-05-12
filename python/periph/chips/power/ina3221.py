import struct


class INA3221Minimal:
    """INA3221 three-channel 26V current/voltage/power monitor — minimal interface.

    Reads bus voltage, shunt voltage, current, and power for each of the three
    channels with no configuration beyond the transport and shunt resistors.
    The chip's power-on default (all three channels on, continuous shunt+bus)
    is used without modification.

    Args:
        transport: Configured I2C or SMBus transport pointing at the device.
        r_shunt: Shunt resistor value in ohms. Pass a single float to apply
            the same value to all three channels, or a 3-element sequence
            (list/tuple) for per-channel values (default 0.1 ohms for all).
    """

    _REG_CONFIG   = 0x00
    _REG_SHUNT1   = 0x01
    _REG_BUS1     = 0x02
    _REG_SHUNT2   = 0x03
    _REG_BUS2     = 0x04
    _REG_SHUNT3   = 0x05
    _REG_BUS3     = 0x06
    _REG_MFR_ID   = 0xFE
    _REG_DIE_ID   = 0xFF

    _SHUNT_REGS = (_REG_SHUNT1, _REG_SHUNT2, _REG_SHUNT3)
    _BUS_REGS   = (_REG_BUS1, _REG_BUS2, _REG_BUS3)

    def __init__(self, transport, r_shunt=0.1):
        self._transport = transport
        if hasattr(r_shunt, '__iter__'):
            self._r_shunt = tuple(float(r_shunt[i]) for i in range(3))
        else:
            self._r_shunt = (float(r_shunt), float(r_shunt), float(r_shunt))

    def _write_reg(self, reg, value):
        self._transport.write(struct.pack('>BH', reg, value))

    def _read_reg(self, reg):
        return struct.unpack('>H', self._transport.write_read(bytes([reg]), 2))[0]

    def _read_reg_signed(self, reg):
        return struct.unpack('>h', self._transport.write_read(bytes([reg]), 2))[0]

    def _channel_valid(self, channel):
        if channel not in (1, 2, 3):
            raise ValueError('channel must be 1, 2, or 3')
        return channel

    def voltage(self, channel):
        """Read bus voltage for a channel.

        Args:
            channel: Channel number 1, 2, or 3.

        Returns:
            float: Bus voltage in volts.
        """
        ch = self._channel_valid(channel)
        raw = self._read_reg(self._BUS_REGS[ch - 1])
        return (raw >> 3) * 8e-3

    def shunt_voltage(self, channel):
        """Read differential shunt voltage for a channel.

        Args:
            channel: Channel number 1, 2, or 3.

        Returns:
            float: Shunt voltage in volts, signed.
        """
        ch = self._channel_valid(channel)
        raw = self._read_reg_signed(self._SHUNT_REGS[ch - 1])
        return raw * 5e-6

    def current(self, channel):
        """Read calculated current through the shunt for a channel.

        Args:
            channel: Channel number 1, 2, or 3.

        Returns:
            float: Current in amperes.
        """
        ch = self._channel_valid(channel)
        return self.shunt_voltage(ch) / self._r_shunt[ch - 1]

    def power(self, channel):
        """Read calculated power for a channel.

        Args:
            channel: Channel number 1, 2, or 3.

        Returns:
            float: Power in watts.
        """
        ch = self._channel_valid(channel)
        return self.voltage(ch) * self.current(ch)


class INA3221Full(INA3221Minimal):
    """INA3221 full interface — extends INA3221Minimal with configuration and alert support.

    Adds Configuration Register programming, channel enables, conversion-ready,
    per-channel critical and warning alerts, shunt-voltage summation, power-valid
    monitoring, reset, and shutdown/wake.

    Alert flag constants (from Mask/Enable register):
        CF1, CF2, CF3 — Channel 1/2/3 critical-alert flag
        WF1, WF2, WF3 — Channel 1/2/3 warning-alert flag
        SF            — Summation-alert flag
        PVF           — Power-valid flag
        TCF           — Timing-control flag
        CVRF          — Conversion-ready flag

    Mode constants:
        MODE_POWERDOWN      = 0
        MODE_SHUNT_TRIG     = 1
        MODE_BUS_TRIG       = 2
        MODE_SHUNT_BUS_TRIG = 3
        MODE_SHUNT_CONT     = 5
        MODE_BUS_CONT       = 6
        MODE_SHUNT_BUS_CONT = 7

    Args:
        transport: Configured I2C or SMBus transport pointing at the device.
        r_shunt: Shunt resistor value in ohms. Pass a single float to apply
            the same value to all three channels, or a 3-element sequence
            (list/tuple) for per-channel values (default 0.1 ohms for all).
    """

    CF1 = 0x0200
    CF2 = 0x0100
    CF3 = 0x0080
    SF  = 0x0040
    WF1 = 0x0020
    WF2 = 0x0010
    WF3 = 0x0008
    PVF = 0x0004
    TCF = 0x0002
    CVRF = 0x0001

    MODE_POWERDOWN      = 0
    MODE_SHUNT_TRIG     = 1
    MODE_BUS_TRIG       = 2
    MODE_SHUNT_BUS_TRIG = 3
    MODE_SHUNT_CONT     = 5
    MODE_BUS_CONT       = 6
    MODE_SHUNT_BUS_CONT = 7

    _REG_CH1_CRIT   = 0x07
    _REG_CH1_WARN   = 0x08
    _REG_CH2_CRIT   = 0x09
    _REG_CH2_WARN   = 0x0A
    _REG_CH3_CRIT   = 0x0B
    _REG_CH3_WARN   = 0x0C
    _REG_SUM        = 0x0D
    _REG_SUM_LIMIT  = 0x0E
    _REG_MASK_EN    = 0x0F
    _REG_PV_UPPER   = 0x10
    _REG_PV_LOWER   = 0x11

    _CRIT_REGS  = (_REG_CH1_CRIT, _REG_CH2_CRIT, _REG_CH3_CRIT)
    _WARN_REGS  = (_REG_CH1_WARN, _REG_CH2_WARN, _REG_CH3_WARN)

    def __init__(self, transport, r_shunt=0.1):
        super().__init__(transport, r_shunt)
        self._mode = 0x07

    def configure(self, avg=0, vbus_ct=4, vsh_ct=4, mode=7):
        """Write the Configuration Register.

        Preserves channel-enable bits (CH1en, CH2en, CH3en).

        Args:
            avg: Averaging count selector 0-7 (0=1 sample, 7=1024 samples).
            vbus_ct: Bus voltage conversion time selector 0-7 (default 4=1.1 ms).
            vsh_ct: Shunt voltage conversion time selector 0-7 (default 4=1.1 ms).
            mode: Operating mode (default 7=shunt+bus continuous).
        """
        cfg = self._read_reg(self._REG_CONFIG)
        config = ((avg & 0x07) << 9) | ((vbus_ct & 0x07) << 6) | ((vsh_ct & 0x07) << 3) | (mode & 0x07)
        config |= cfg & 0x7000
        self._mode = mode & 0x07
        self._write_reg(self._REG_CONFIG, config)

    def enable_channel(self, channel, enabled):
        """Enable or disable a channel.

        Args:
            channel: Channel number 1, 2, or 3.
            enabled: True to enable, False to disable.
        """
        ch = self._channel_valid(channel)
        cfg = self._read_reg(self._REG_CONFIG)
        bit = 14 - (ch - 1)
        if enabled:
            cfg |= (1 << bit)
        else:
            cfg &= ~(1 << bit)
        self._write_reg(self._REG_CONFIG, cfg)

    def channel_enabled(self, channel):
        """Read whether a channel is enabled.

        Args:
            channel: Channel number 1, 2, or 3.

        Returns:
            bool: True if the channel is enabled.
        """
        ch = self._channel_valid(channel)
        cfg = self._read_reg(self._REG_CONFIG)
        bit = 14 - (ch - 1)
        return bool(cfg & (1 << bit))

    def conversion_ready(self):
        """Read the Conversion Ready Flag (CVRF).

        Returns:
            bool: True if a conversion completed.
        """
        return bool(self._read_reg(self._REG_MASK_EN) & self.CVRF)

    def set_critical_alert(self, channel, limit_v, latch=False):
        """Set the critical-alert limit for a channel.

        Args:
            channel: Channel number 1, 2, or 3.
            limit_v: Voltage limit in volts.
            latch: If True, use latched mode (default False).
        """
        ch = self._channel_valid(channel)
        raw = (int(limit_v / 40e-6) << 3) & 0xFFF8
        self._write_reg(self._CRIT_REGS[ch - 1], raw)
        cfg = self._read_reg(self._REG_MASK_EN)
        if latch:
            cfg |= 0x0400
        else:
            cfg &= ~0x0400
        self._write_reg(self._REG_MASK_EN, cfg)

    def set_warning_alert(self, channel, limit_v, latch=False):
        """Set the warning-alert limit for a channel.

        Args:
            channel: Channel number 1, 2, or 3.
            limit_v: Voltage limit in volts.
            latch: If True, use latched mode (default False).
        """
        ch = self._channel_valid(channel)
        raw = (int(limit_v / 40e-6) << 3) & 0xFFF8
        self._write_reg(self._WARN_REGS[ch - 1], raw)
        cfg = self._read_reg(self._REG_MASK_EN)
        if latch:
            cfg |= 0x0800
        else:
            cfg &= ~0x0800
        self._write_reg(self._REG_MASK_EN, cfg)

    def alert_flags(self):
        """Read the Mask/Enable Register.

        Reading this register clears the latched alert flags (CF1/CF2/CF3,
        WF1/WF2/WF3, SF) when latch mode is enabled.

        Returns:
            int: Raw 16-bit Mask/Enable register value.
        """
        return self._read_reg(self._REG_MASK_EN)

    def set_summation_channels(self, channels, limit_v):
        """Configure the shunt-voltage summation function.

        Args:
            channels: List of channel numbers to sum (e.g. [1, 2, 3]).
            limit_v: Shunt-voltage sum limit in volts.
        """
        cfg = self._read_reg(self._REG_MASK_EN)
        cfg &= ~0xE000
        for ch in channels:
            self._channel_valid(ch)
            cfg |= 1 << (15 - (ch - 1))
        self._write_reg(self._REG_MASK_EN, cfg)
        raw = (int(limit_v / 40e-6) << 1) & 0xFFFE
        self._write_reg(self._REG_SUM_LIMIT, raw)

    def summation_value(self):
        """Read the shunt-voltage sum.

        Returns:
            float: Sum of selected channels' shunt voltages in volts.
        """
        raw = self._read_reg_signed(self._REG_SUM)
        return raw * 5e-6

    def set_power_valid_limits(self, upper_v, lower_v):
        """Set the Power-Valid upper and lower voltage limits.

        Args:
            upper_v: Upper bus voltage limit in volts.
            lower_v: Lower bus voltage limit in volts.
        """
        raw_upper = (int(upper_v / 8e-3) << 3) & 0xFFF8
        raw_lower = (int(lower_v / 8e-3) << 3) & 0xFFF8
        self._write_reg(self._REG_PV_UPPER, raw_upper)
        self._write_reg(self._REG_PV_LOWER, raw_lower)

    def power_valid(self):
        """Read the Power-Valid flag (PVF).

        Returns:
            bool: True if all enabled bus voltages are within the PV limits.
        """
        return bool(self._read_reg(self._REG_MASK_EN) & self.PVF)

    def shutdown(self):
        """Enter power-down mode and save the current mode for wake()."""
        cfg = self._read_reg(self._REG_CONFIG)
        self._mode = cfg & 0x07
        self._write_reg(self._REG_CONFIG, cfg & 0xFFF8)

    def wake(self):
        """Restore the operating mode saved by shutdown()."""
        cfg = self._read_reg(self._REG_CONFIG)
        self._write_reg(self._REG_CONFIG, (cfg & 0xFFF8) | self._mode)

    def reset(self):
        """Reset all registers to power-on defaults."""
        self._write_reg(self._REG_CONFIG, 0x8000)

    def manufacturer_id(self):
        """Read the Manufacturer ID register.

        Returns:
            int: Manufacturer ID; expect 0x5449 (Texas Instruments).
        """
        return self._read_reg(self._REG_MFR_ID)

    def die_id(self):
        """Read the Die ID register.

        Returns:
            int: Die revision ID; expect 0x3220.
        """
        return self._read_reg(self._REG_DIE_ID)