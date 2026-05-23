import struct


class AS5600Minimal:
    """AS5600 12-bit programmable contactless rotary position sensor — minimal interface.

    Reads the absolute angle in degrees with no configuration required beyond the
    transport. Verifies magnet presence at construction; raises if no magnet is detected.

    Default behaviour:
        - Reads STATUS to verify MD=1 (magnet detected) at construction
        - Reads ANGLE register (0x0E-0x0F), respecting any OTP-programmed ZPOS/MPOS range
        - No CONF writes — uses power-on default CONF=0x0000

    Args:
        transport: Configured I²C transport pointing at the device (fixed address 0x36).
    """

    _REG_ZMCO      = 0x00
    _REG_ZPOS_H    = 0x01
    _REG_ZPOS_L    = 0x02
    _REG_MPOS_H    = 0x03
    _REG_MPOS_L    = 0x04
    _REG_MANG_H    = 0x05
    _REG_MANG_L    = 0x06
    _REG_CONF_H    = 0x07
    _REG_CONF_L    = 0x08
    _REG_STATUS    = 0x0B
    _REG_RAW_ANGLE_H = 0x0C
    _REG_RAW_ANGLE_L = 0x0D
    _REG_ANGLE_H   = 0x0E
    _REG_ANGLE_L   = 0x0F
    _REG_AGC       = 0x1A
    _REG_MAGNITUDE_H = 0x1B
    _REG_MAGNITUDE_L = 0x1C
    _REG_BURN      = 0xFF

    _STATUS_MD = 0x08
    _STATUS_ML = 0x10
    _STATUS_MH = 0x20

    def __init__(self, transport):
        self._transport = transport
        status = self._read_reg8(self._REG_STATUS)
        if not (status & self._STATUS_MD):
            raise RuntimeError('AS5600: magnet not detected (MD=0)')

    def _read_reg8(self, reg):
        return self._transport.write_read(bytes([reg]), 1)[0]

    def _read_reg16(self, reg):
        raw = self._transport.write_read(bytes([reg]), 2)
        return (raw[0] << 8) | raw[1]

    def _write_reg8(self, reg, value):
        self._transport.write(struct.pack('>BB', reg, value))

    def _write_reg16(self, reg, value):
        self._transport.write(struct.pack('>BH', reg, value))

    def angle(self):
        """Read the scaled absolute angle.

        Returns:
            float: Angle in degrees, 0.0–360.0 (exclusive).
        """
        return self.angle_raw() * 360.0 / 4096

    def angle_raw(self):
        """Read the scaled 12-bit angle count.

        Returns:
            int: Scaled angle count, 0–4095 (respects ZPOS/MPOS if programmed).
        """
        raw = self._read_reg16(self._REG_ANGLE_H)
        return raw & 0x0FFF

    def is_magnet_detected(self):
        """Check if a magnet is detected.

        Returns:
            bool: True if STATUS.MD=1 (magnetic field >= 8 mT).
        """
        return bool(self._read_reg8(self._REG_STATUS) & self._STATUS_MD)

    def is_magnet_too_strong(self):
        """Check if the magnet is too strong.

        Returns:
            bool: True if STATUS.MH=1 (AGC minimum gain overflow, Bz > 90 mT).
        """
        return bool(self._read_reg8(self._REG_STATUS) & self._STATUS_MH)

    def is_magnet_too_weak(self):
        """Check if the magnet is too weak.

        Returns:
            bool: True if STATUS.ML=1 (AGC maximum gain overflow, Bz < 30 mT).
        """
        return bool(self._read_reg8(self._REG_STATUS) & self._STATUS_ML)


class AS5600Full(AS5600Minimal):
    """AS5600 full interface — extends AS5600Minimal with complete chip functionality.

    Adds raw angle readings, AGC/magnitude/status access, configuration,
    ZPOS/MPOS/MANG programming, and OTP burn commands.

    Power mode constants (pass to configure):
        PM_NOM  = 0: normal mode (6.5 mA)
        PM_LPM1 = 1: low power 1 (3.4 mA, 5 ms poll)
        PM_LPM2 = 2: low power 2 (1.8 mA, 20 ms poll)
        PM_LPM3 = 3: low power 3 (1.5 mA, 100 ms poll)

    Output stage constants:
        OUTS_ANALOG  = 0: analog 0–VDD
        OUTS_ANALOG2 = 1: analog 10–90% VDD
        OUTS_PWM     = 2: digital PWM

    Args:
        transport: Configured I²C transport pointing at the device (fixed address 0x36).
    """

    PM_NOM  = 0
    PM_LPM1 = 1
    PM_LPM2 = 2
    PM_LPM3 = 3

    OUTS_ANALOG  = 0
    OUTS_ANALOG2 = 1
    OUTS_PWM     = 2

    _BURN_ANGLE  = 0x80
    _BURN_SETTING = 0x40

    def raw_angle(self):
        """Read the unscaled raw 12-bit angle count.

        Returns:
            int: Raw angle count, 0–4095 (unaffected by ZPOS/MPOS).
        """
        raw = self._read_reg16(self._REG_RAW_ANGLE_H)
        return raw & 0x0FFF

    def raw_angle_degrees(self):
        """Read the unscaled raw angle in degrees.

        Returns:
            float: Raw angle in degrees, 0.0–360.0.
        """
        return self.raw_angle() * 360.0 / 4096

    def agc(self):
        """Read the automatic gain control value.

        Returns:
            int: AGC value (0–255 in 5 V mode; 0–127 in 3.3 V mode).
                Mid-range indicates optimal airgap.
        """
        return self._read_reg8(self._REG_AGC)

    def magnitude(self):
        """Read the CORDIC magnitude value.

        Returns:
            int: 12-bit CORDIC magnitude value.
        """
        raw = self._read_reg16(self._REG_MAGNITUDE_H)
        return raw & 0x0FFF

    def status_byte(self):
        """Read the raw STATUS register byte.

        Returns:
            int: Raw STATUS register (bits MH, ML, MD in positions 5, 4, 3).
        """
        return self._read_reg8(self._REG_STATUS)

    def configure(self, pm=0, hyst=0, outs=0, pwmf=0, sf=0, fth=0, wd=False):
        """Write the CONF_H and CONF_L registers.

        Reads the current CONF_H/CONF_L values first to preserve the reserved
        bits in CONF_H[7:6].

        Args:
            pm: Power mode 0–3 (0=NOM, 1=LPM1, 2=LPM2, 3=LPM3).
            hyst: Hysteresis 0–3 (0=off, 1=1 LSB, 2=2 LSBs, 3=3 LSBs).
            outs: Output stage 0–2 (0=analog 0–VDD, 1=analog 10–90%, 2=PWM).
            pwmf: PWM frequency 0–3 (0=115 Hz, 1=230 Hz, 2=460 Hz, 3=920 Hz).
            sf: Slow filter 0–3 (0=16x, 1=8x, 2=4x, 3=2x).
            fth: Fast filter threshold 0–7.
            wd: Watchdog enable (True=on, False=off).
        """
        conf_h = self._read_reg8(self._REG_CONF_H)
        conf_l = self._read_reg8(self._REG_CONF_L)
        conf_h = (conf_h & 0xC0) | ((wd & 1) << 5) | ((fth & 0x07) << 2) | (sf & 0x03)
        conf_l = ((pwmf & 0x03) << 6) | ((outs & 0x03) << 4) | ((hyst & 0x03) << 2) | (pm & 0x03)
        self._write_reg16(self._REG_CONF_H, (conf_h << 8) | conf_l)

    def set_zero_position(self, pos):
        """Write the zero position (start angle) to volatile RAM.

        Args:
            pos: Zero position 0–4095. Lost on power cycle unless burned.
        """
        self._write_reg8(self._REG_ZPOS_H, (pos >> 8) & 0x0F)
        self._write_reg8(self._REG_ZPOS_L, pos & 0xFF)

    def set_max_position(self, pos):
        """Write the maximum position (stop angle) to volatile RAM.

        Args:
            pos: Maximum position 0–4095. Lost on power cycle unless burned.
        """
        self._write_reg8(self._REG_MPOS_H, (pos >> 8) & 0x0F)
        self._write_reg8(self._REG_MPOS_L, pos & 0xFF)

    def set_max_angle(self, span):
        """Write the maximum angle span to volatile RAM.

        Args:
            span: Angle span 0–4095 (must correspond to >= 18 degrees).
        """
        self._write_reg8(self._REG_MANG_H, (span >> 8) & 0x0F)
        self._write_reg8(self._REG_MANG_L, span & 0xFF)

    def zero_position(self):
        """Read the zero position (start angle).

        Returns:
            int: ZPOS value 0–4095.
        """
        raw = self._read_reg16(self._REG_ZPOS_H)
        return raw & 0x0FFF

    def max_position(self):
        """Read the maximum position (stop angle).

        Returns:
            int: MPOS value 0–4095.
        """
        raw = self._read_reg16(self._REG_MPOS_H)
        return raw & 0x0FFF

    def max_angle(self):
        """Read the maximum angle span.

        Returns:
            int: MANG value 0–4095.
        """
        raw = self._read_reg16(self._REG_MANG_H)
        return raw & 0x0FFF

    def burn_count(self):
        """Read the number of permanent ZPOS/MPOS burns already performed.

        Returns:
            int: ZMCO value 0–3. Remaining permanent writes = 3 - ZMCO.
        """
        return self._read_reg8(self._REG_ZMCO) & 0x03

    def burn_angle(self):
        """Permanently burn ZPOS and MPOS to OTP.

        Requires MD=1 (magnet present) and ZMCO < 3. After burning, waits 1 ms
        then verifies by writing 0x01, 0x11, 0x10 to 0xFF and re-reading ZPOS/MPOS.

        Raises:
            RuntimeError: If magnet not detected (MD=0) or ZMCO >= 3.
        """
        status = self._read_reg8(self._REG_STATUS)
        if not (status & self._STATUS_MD):
            raise RuntimeError('AS5600: cannot burn angle — magnet not detected')
        zmco = self._read_reg8(self._REG_ZMCO) & 0x03
        if zmco >= 3:
            raise RuntimeError('AS5600: cannot burn angle — ZMCO limit reached (3)')
        self._write_reg8(self._REG_BURN, self._BURN_ANGLE)

    def burn_setting(self):
        """Permanently burn MANG and CONF to OTP.

        Requires ZMCO=0 (ZPOS/MPOS never burned). Can only be executed once.

        Raises:
            RuntimeError: If ZMCO != 0.
        """
        zmco = self._read_reg8(self._REG_ZMCO) & 0x03
        if zmco != 0:
            raise RuntimeError('AS5600: cannot burn setting — ZMCO must be 0')
        self._write_reg8(self._REG_BURN, self._BURN_SETTING)
