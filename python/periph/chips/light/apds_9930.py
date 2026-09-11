"""APDS-9930 digital ambient light and proximity sensor.

Provides dual-channel ambient light sensing (with on-chip IR compensation
for human-eye lux) and proximity detection using the chip's integrated
850 nm IR LED and synchronous driver. Communicates over I²C; every
register access is prefixed by a command byte (0x80|reg for write,
0xA0|reg for the recommended auto-increment 16-bit read).

Default configuration baked into the Minimal stage:

    ATIME  = 0xDB (101 ms integration — rejects 50/60 Hz fluorescent flicker)
    PTIME  = 0xFF (2.73 ms proximity ADC time, datasheet-recommended default)
    PPULSE = 0x08 (8 LED pulses — factory-calibrated for 100 mm range)
    CONTROL= 0x20 (PDIODE=Ch1, PDRIVE=100 mA, PGAIN=1x, AGAIN=1x)
    ENABLE = 0x07 (PON + AEN + PEN; wait timer and interrupts disabled)

I²C address: 0x39 (fixed).
"""

import time


_CMD_WRITE = 0x80
_CMD_READ   = 0xA0


def _encode_offset(value):
    """Encode a signed offset (-127..+127) into the sign-magnitude POFFSET byte.

    Bit 7 = sign (1 = positive, 0 = negative per datasheet convention).
    Bits 6:0 = magnitude (0-127).
    """
    if value >= 0:
        return 0x80 | (value & 0x7F)
    return (-value) & 0x7F


def _again_factor(again_idx, agl):
    """Resolve effective AGAIN multiplier from the AGAIN index and AGL flag.

    Per datasheet: with AGL=0, factors are 1, 8, 16, 120; with AGL=1,
    factors become 1/6, 8/6, 16/6, 20 (the spec only lists the first
    three AGAIN levels as divided and rolls the 120x case into 20x).
    """
    if not agl:
        return (1, 8, 16, 120)[again_idx & 0x03]
    return (1.0 / 6.0, 8.0 / 6.0, 16.0 / 6.0, 20.0)[again_idx & 0x03]


class APDS9930Minimal:
    """APDS-9930 ambient light and proximity sensor — minimal interface.

    Provides illuminance (lux) and raw proximity count with no
    configuration required beyond the connection. The ALS and proximity
    engines are enabled at construction with sensible defaults that give
    stable readings under typical indoor/outdoor lighting.

    Args:
        connection: Configured I2C connection pointing at the device (address 0x39).

    Raises:
        ValueError: If the ID register does not read back 0x39.
    """

    _REG_ENABLE   = 0x00
    _REG_ATIME    = 0x01
    _REG_PTIME    = 0x02
    _REG_WTIME    = 0x03
    _REG_AILTL    = 0x04
    _REG_AILTH    = 0x05
    _REG_AIHTL    = 0x06
    _REG_AIHTH    = 0x07
    _REG_PILTL    = 0x08
    _REG_PILTH    = 0x09
    _REG_PIHTL    = 0x0A
    _REG_PIHTH    = 0x0B
    _REG_PERS     = 0x0C
    _REG_CONFIG   = 0x0D
    _REG_PPULSE   = 0x0E
    _REG_CONTROL  = 0x0F
    _REG_ID       = 0x12
    _REG_STATUS   = 0x13
    _REG_CH0DATAL = 0x14
    _REG_CH0DATAH = 0x15
    _REG_CH1DATAL = 0x16
    _REG_CH1DATAH = 0x17
    _REG_PDATAL   = 0x18
    _REG_PDATAH   = 0x19
    _REG_POFFSET  = 0x1E

    _CFN_NOOP              = 0x00
    _CFN_CLEAR_PROXIMITY   = 0x05
    _CFN_CLEAR_ALS         = 0x06
    _CFN_CLEAR_BOTH        = 0x07
    _CMD_SPECIAL = 0xE0

    _ATIME_DEFAULT   = 0xDB
    _PTIME_DEFAULT   = 0xFF
    _PPULSE_DEFAULT  = 0x08
    _CONTROL_DEFAULT = 0x20
    _ENABLE_DEFAULT  = 0x07

    def __init__(self, connection):
        self._connection = connection
        chip_id = self._read_reg(self._REG_ID)
        if chip_id != 0x39:
            raise ValueError(
                'APDS-9930 not found (ID=0x{:02X}, expected 0x39)'.format(chip_id)
            )
        self._write_reg(self._REG_ENABLE, 0x00)
        self._write_reg(self._REG_ATIME, self._ATIME_DEFAULT)
        self._write_reg(self._REG_PTIME, self._PTIME_DEFAULT)
        self._write_reg(self._REG_PPULSE, self._PPULSE_DEFAULT)
        self._write_reg(self._REG_CONTROL, self._CONTROL_DEFAULT)
        self._write_reg(self._REG_ENABLE, self._ENABLE_DEFAULT)
        time.sleep(0.012)

    def _write_reg(self, reg, value):
        self._connection.write(bytes([_CMD_WRITE | (reg & 0x1F), value & 0xFF]))

    def _read_reg(self, reg):
        return self._connection.write_read(bytes([_CMD_READ | (reg & 0x1F)]), 1)[0]

    def _read_reg16(self, reg):
        raw = self._connection.write_read(bytes([_CMD_READ | (reg & 0x1F)]), 2)
        return (raw[1] << 8) | raw[0]

    def _special(self, function_code):
        self._connection.write(bytes([self._CMD_SPECIAL | (function_code & 0x1F)]))

    def lux(self):
        """Read the ambient illuminance.

        Uses the chip's two-channel architecture (Ch0 = visible + IR,
        Ch1 = IR-only) to compensate for the IR component of ambient
        light, then applies the open-air lux coefficients from the
        datasheet.

        Returns:
            float: Illuminance in lux.
        """
        ch0 = self._read_reg16(self._REG_CH0DATAL)
        ch1 = self._read_reg16(self._REG_CH1DATAL)
        ctrl = self._read_reg(self._REG_CONTROL)
        again_idx = ctrl & 0x03
        cfg = self._read_reg(self._REG_CONFIG)
        agl = bool(cfg & 0x04)
        again_x = _again_factor(again_idx, agl)
        atime = self._read_reg(self._REG_ATIME)
        alsit_ms = 2.73 * (256 - atime)
        iac1 = ch0 - 1.862 * ch1
        iac2 = 0.746 * ch0 - 1.291 * ch1
        iac = iac1
        if iac2 > iac:
            iac = iac2
        if iac < 0:
            iac = 0
        lpc = (0.49 * 52.0) / (alsit_ms * again_x)
        return iac * lpc

    def proximity(self):
        """Read the proximity ADC count.

        Higher counts mean a closer object. Realistically limited to
        10 bits (0-1023) at the default PTIME=0xFF (one ADC cycle).

        Returns:
            int: Raw proximity count, 0-1023.
        """
        return self._read_reg16(self._REG_PDATAL)


class APDS9930Full(APDS9930Minimal):
    """APDS-9930 full interface — extends APDS9930Minimal.

    Adds ALS/proximity integration-time and gain configuration, raw
    channel access, interrupt thresholds with persistence, status
    decoding, sleep-after-interrupt, and proximity offset compensation.

    Args:
        connection: Configured I2C connection pointing at the device (address 0x39).
    """

    def __init__(self, connection):
        super().__init__(connection)

    def configure_als(self, atime=0xDB, again=0, agl=False):
        """Configure ALS integration time and gain.

        Args:
            atime: ATIME register value 0-255 (cycles = 256 - atime, each 2.73 ms).
            again: ALS gain index 0-3 (0=1x, 1=8x, 2=16x, 3=120x).
            agl: True to enable the AGL divide-by-6 gain-level bit (CONFIG.AGL=1).
        """
        self._write_reg(self._REG_ATIME, atime & 0xFF)
        ctrl = self._read_reg(self._REG_CONTROL)
        ctrl = (ctrl & 0xFC) | (again & 0x03)
        self._write_reg(self._REG_CONTROL, ctrl)
        cfg = self._read_reg(self._REG_CONFIG)
        if agl:
            cfg |= 0x04
        else:
            cfg &= ~0x04
        cfg &= ~0x06
        self._write_reg(self._REG_CONFIG, cfg)

    def configure_proximity(self, ppulse=8, pgain=0, pdrive=0, pdl=False, ptime=0xFF):
        """Configure proximity LED pulses, gain, drive, and ADC integration time.

        Args:
            ppulse: Number of LED pulses 1-255.
            pgain: Proximity gain index 0-3 (0=1x, 1=2x, 2=4x, 3=8x).
            pdrive: LED drive current index 0-3 (0=100 mA, 1=50 mA, 2=25 mA, 3=12.5 mA).
            pdl: True to enable PDL (reduces drive to 1/9 of PDRIVE).
            ptime: PTIME register value 0-255.
        """
        self._write_reg(self._REG_PPULSE, ppulse & 0xFF)
        self._write_reg(self._REG_PTIME, ptime & 0xFF)
        ctrl = self._read_reg(self._REG_CONTROL)
        ctrl = (ctrl & 0x03) | ((pdrive & 0x03) << 6) | 0x20 | ((pgain & 0x03) << 2)
        self._write_reg(self._REG_CONTROL, ctrl)
        cfg = self._read_reg(self._REG_CONFIG)
        if pdl:
            cfg |= 0x01
        else:
            cfg &= ~0x01
        cfg &= ~0x06
        self._write_reg(self._REG_CONFIG, cfg)

    def configure_wait(self, wtime=0xFF, wlong=False):
        """Configure wait time and enable the wait timer.

        Args:
            wtime: WTIME register value 0-255 (cycles = 256 - wtime, each 2.73 ms).
            wlong: True to enable WLONG (multiplies wait by 12x).
        """
        self._write_reg(self._REG_WTIME, wtime & 0xFF)
        cfg = self._read_reg(self._REG_CONFIG)
        if wlong:
            cfg |= 0x02
        else:
            cfg &= ~0x02
        cfg &= ~0x04
        self._write_reg(self._REG_CONFIG, cfg)
        en = self._read_reg(self._REG_ENABLE)
        en |= 0x08
        self._write_reg(self._REG_ENABLE, en)

    def disable_wait(self):
        """Clear WEN in ENABLE (disables the wait timer between cycles)."""
        en = self._read_reg(self._REG_ENABLE)
        en &= ~0x08
        self._write_reg(self._REG_ENABLE, en)

    def ch0(self):
        """Read the raw Ch0 (visible + IR) ADC count.

        Returns:
            int: 16-bit ADC count.
        """
        return self._read_reg16(self._REG_CH0DATAL)

    def ch1(self):
        """Read the raw Ch1 (IR-only) ADC count.

        Returns:
            int: 16-bit ADC count.
        """
        return self._read_reg16(self._REG_CH1DATAL)

    def status(self):
        """Read the STATUS register decoded into named fields.

        Returns:
            dict: {avalid, pvalid, psat, aint, pint} as booleans.
        """
        s = self._read_reg(self._REG_STATUS)
        return {
            'avalid': bool(s & 0x01),
            'pvalid': bool(s & 0x02),
            'psat':   bool(s & 0x40),
            'aint':   bool(s & 0x10),
            'pint':   bool(s & 0x20),
        }

    def set_als_thresholds(self, low, high, persistence=1):
        """Set ALS interrupt thresholds and enable AIEN.

        Thresholds are evaluated against raw Ch0 counts, not lux.

        Args:
            low:  16-bit low threshold.
            high: 16-bit high threshold.
            persistence: APERS value 0-15 (0=every, 1=1, 2=2, 3=3, 4=5, 5=10,
                         6=15, 7=20, 8=25, 9=30, 10=35, 11=40, 12=45, 13=50,
                         14=55, 15=60 consecutive out-of-range cycles).
        """
        if low > high:
            high = low
        self._write_reg(self._REG_AILTL, low & 0xFF)
        self._write_reg(self._REG_AILTH, (low >> 8) & 0xFF)
        self._write_reg(self._REG_AIHTL, high & 0xFF)
        self._write_reg(self._REG_AIHTH, (high >> 8) & 0xFF)
        pers = self._read_reg(self._REG_PERS)
        pers = (pers & 0xF0) | (persistence & 0x0F)
        self._write_reg(self._REG_PERS, pers)
        en = self._read_reg(self._REG_ENABLE)
        en |= 0x10
        self._write_reg(self._REG_ENABLE, en)

    def set_proximity_thresholds(self, low, high, persistence=1):
        """Set proximity interrupt thresholds and enable PIEN.

        Args:
            low:  16-bit low threshold.
            high: 16-bit high threshold.
            persistence: PPERS value 0-15 (0=every, 1-15=N consecutive).
        """
        if low > high:
            high = low
        self._write_reg(self._REG_PILTL, low & 0xFF)
        self._write_reg(self._REG_PILTH, (low >> 8) & 0xFF)
        self._write_reg(self._REG_PIHTL, high & 0xFF)
        self._write_reg(self._REG_PIHTH, (high >> 8) & 0xFF)
        pers = self._read_reg(self._REG_PERS)
        pers = (pers & 0x0F) | ((persistence & 0x0F) << 4)
        self._write_reg(self._REG_PERS, pers)
        en = self._read_reg(self._REG_ENABLE)
        en |= 0x20
        self._write_reg(self._REG_ENABLE, en)

    def clear_interrupt(self, channel='both'):
        """Clear pending interrupt(s).

        Args:
            channel: 'als', 'proximity', or 'both'.
        """
        if channel == 'als':
            self._special(self._CFN_CLEAR_ALS)
        elif channel == 'proximity':
            self._special(self._CFN_CLEAR_PROXIMITY)
        else:
            self._special(self._CFN_CLEAR_BOTH)

    def set_proximity_offset(self, offset):
        """Set the proximity offset (sign-magnitude).

        Args:
            offset: Signed integer -127..+127 (positive shifts data up).
        """
        self._write_reg(self._REG_POFFSET, _encode_offset(offset))

    def sleep_after_interrupt(self, enable):
        """Enable or disable SAI (sleep after interrupt) in ENABLE.

        Args:
            enable: True to set SAI, False to clear it.
        """
        en = self._read_reg(self._REG_ENABLE)
        if enable:
            en |= 0x40
        else:
            en &= ~0x40
        self._write_reg(self._REG_ENABLE, en)