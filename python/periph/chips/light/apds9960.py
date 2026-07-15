import struct
import time


class APDS9960Minimal:
    """APDS-9960 digital proximity, ambient light, RGB and gesture sensor — minimal interface.

    Provides ambient light and color (RGBC) readings with no configuration
    beyond the transport. The ALS/Color engine is enabled at construction
    with sensible defaults.

    Default configuration (baked in at construction):
        - ATIME = 0xB6 (72 cycles, ~200 ms integration, max count 65535)
        - AGAIN = 1 (4x ALS gain, CONTROL bits 1:0)
        - CONFIG2 = 0x01 (LED_BOOST=100%, reserved bit 0 set)
        - PON + AEN enabled; no wait, proximity, gesture, or interrupts

    Args:
        transport: Configured I2C transport pointing at the device (address 0x39).
    """

    _REG_ENABLE   = 0x80
    _REG_ATIME    = 0x81
    _REG_WTIME    = 0x83
    _REG_AILTL    = 0x84
    _REG_AILTH    = 0x85
    _REG_AIHTL    = 0x86
    _REG_AIHTH    = 0x87
    _REG_PILT     = 0x89
    _REG_PIHT     = 0x8B
    _REG_PERS     = 0x8C
    _REG_CONFIG1  = 0x8D
    _REG_PPULSE   = 0x8E
    _REG_CONTROL  = 0x8F
    _REG_CONFIG2  = 0x90
    _REG_ID       = 0x92
    _REG_STATUS   = 0x93
    _REG_CDATAL   = 0x94
    _REG_CDATAH   = 0x95
    _REG_RDATAL   = 0x96
    _REG_RDATAH   = 0x97
    _REG_GDATAL   = 0x98
    _REG_GDATAH   = 0x99
    _REG_BDATAL   = 0x9A
    _REG_BDATAH   = 0x9B
    _REG_PDATA    = 0x9C
    _REG_POFFSET_UR = 0x9D
    _REG_POFFSET_DL = 0x9E
    _REG_CONFIG3  = 0x9F
    _REG_GPENTH   = 0xA0
    _REG_GEXTH    = 0xA1
    _REG_GCONF1   = 0xA2
    _REG_GCONF2   = 0xA3
    _REG_GOFFSET_U = 0xA4
    _REG_GOFFSET_D = 0xA5
    _REG_GPULSE   = 0xA6
    _REG_GOFFSET_L = 0xA7
    _REG_GOFFSET_R = 0xA9
    _REG_GCONF3   = 0xAA
    _REG_GCONF4   = 0xAB
    _REG_GFLVL    = 0xAE
    _REG_GSTATUS  = 0xAF
    _REG_IFORCE   = 0xE4
    _REG_PICLEAR  = 0xE5
    _REG_CICLEAR  = 0xE6
    _REG_AICLEAR  = 0xE7
    _REG_GFIFO_U  = 0xFC

    _ATIME_DEFAULT = 0xB6
    _CONTROL_DEFAULT = 0x01
    _CONFIG2_DEFAULT = 0x01

    def __init__(self, transport):
        self._transport = transport
        time.sleep(0.006)
        self._write_reg(self._REG_ENABLE, 0x00)
        self._write_reg(self._REG_ATIME, self._ATIME_DEFAULT)
        self._write_reg(self._REG_CONTROL, self._CONTROL_DEFAULT)
        self._write_reg(self._REG_CONFIG2, self._CONFIG2_DEFAULT)
        self._write_reg(self._REG_ENABLE, 0x03)
        time.sleep(0.210)

    def _write_reg(self, reg, value):
        self._transport.write(bytes([reg, value]))

    def _read_reg(self, reg):
        return self._transport.write_read(bytes([reg]), 1)[0]

    def _read_reg16_le(self, reg):
        raw = self._transport.write_read(bytes([reg]), 2)
        return raw[0] | (raw[1] << 8)

    def color_clear(self):
        """Read the clear (unfiltered) channel.

        Returns:
            int: Raw clear channel count, 0-65535.
        """
        return self._read_reg16_le(self._REG_CDATAL)

    def color_red(self):
        """Read the red channel.

        Burst-reads all 8 bytes from CDATAL to trigger the atomic latch.

        Returns:
            int: Raw red channel count, 0-65535.
        """
        raw = self._transport.write_read(bytes([self._REG_CDATAL]), 8)
        return raw[2] | (raw[3] << 8)

    def color_green(self):
        """Read the green channel.

        Burst-reads all 8 bytes from CDATAL to trigger the atomic latch.

        Returns:
            int: Raw green channel count, 0-65535.
        """
        raw = self._transport.write_read(bytes([self._REG_CDATAL]), 8)
        return raw[4] | (raw[5] << 8)

    def color_blue(self):
        """Read the blue channel.

        Burst-reads all 8 bytes from CDATAL to trigger the atomic latch.

        Returns:
            int: Raw blue channel count, 0-65535.
        """
        raw = self._transport.write_read(bytes([self._REG_CDATAL]), 8)
        return raw[6] | (raw[7] << 8)

    def color(self):
        """Read all four RGBC channels in one burst.

        Reading CDATAL at 0x94 atomically latches all eight bytes 0x94-0x9B.

        Returns:
            tuple: (clear, red, green, blue) each 0-65535.
        """
        raw = self._transport.write_read(bytes([self._REG_CDATAL]), 8)
        c = raw[0] | (raw[1] << 8)
        r = raw[2] | (raw[3] << 8)
        g = raw[4] | (raw[5] << 8)
        b = raw[6] | (raw[7] << 8)
        return (c, r, g, b)


class APDS9960Full(APDS9960Minimal):
    """APDS-9960 full interface — extends APDS9960Minimal with proximity, gesture, and configuration.

    Adds proximity detection, gesture engine, wait engine, threshold and
    interrupt configuration, status queries, and device identification.

    Args:
        transport: Configured I2C transport pointing at the device (address 0x39).
    """

    def __init__(self, transport):
        super().__init__(transport)

    def enable_proximity(self, enabled):
        """Enable or disable the proximity engine.

        Args:
            enabled: True to enable PEN, False to disable.
        """
        val = self._read_reg(self._REG_ENABLE)
        if enabled:
            val |= 0x04
        else:
            val &= ~0x04
        self._write_reg(self._REG_ENABLE, val)

    def proximity(self):
        """Read the proximity count.

        Returns:
            int: Proximity count 0-255; higher means closer.
        """
        return self._read_reg(self._REG_PDATA)

    def enable_wait(self, enabled):
        """Enable or disable the wait engine.

        Args:
            enabled: True to enable WEN, False to disable.
        """
        val = self._read_reg(self._REG_ENABLE)
        if enabled:
            val |= 0x08
        else:
            val &= ~0x08
        self._write_reg(self._REG_ENABLE, val)

    def configure_wait(self, wtime, long=False):
        """Configure the wait time between ALS/proximity cycles.

        Args:
            wtime: WTIME register value 0-255 (wait = (256 - wtime) * 2.78 ms).
            long: True to enable WLONG 12x multiplier in CONFIG1.
        """
        self._write_reg(self._REG_WTIME, wtime & 0xFF)
        c1 = self._read_reg(self._REG_CONFIG1)
        if long:
            c1 |= 0x02
        else:
            c1 &= ~0x02
        c1 = (c1 & 0x03) | 0x60
        self._write_reg(self._REG_CONFIG1, c1)

    def configure_als(self, atime, again):
        """Configure ALS integration time and gain.

        Args:
            atime: ATIME register value 0-255 (cycles = 256 - atime, each 2.78 ms).
            again: ALS gain 0-3 (0=1x, 1=4x, 2=16x, 3=64x).
        """
        self._write_reg(self._REG_ATIME, atime & 0xFF)
        ctrl = self._read_reg(self._REG_CONTROL)
        ctrl = (ctrl & 0xFC) | (again & 0x03)
        self._write_reg(self._REG_CONTROL, ctrl)

    def configure_proximity_led(self, ldrive, pgain, ppulse, pplen):
        """Configure proximity LED drive, gain, pulse count and length.

        Args:
            ldrive: LED drive strength 0-3 (0=100mA, 1=50mA, 2=25mA, 3=12.5mA).
            pgain: Proximity gain 0-3 (0=1x, 1=2x, 2=4x, 3=8x).
            ppulse: Pulse count minus 1, 0-63 (0=1 pulse, 63=64 pulses).
            pplen: Pulse length 0-3 (0=4us, 1=8us, 2=16us, 3=32us).
        """
        ctrl = self._read_reg(self._REG_CONTROL)
        ctrl = ((ldrive & 0x03) << 6) | ((pgain & 0x03) << 2) | (ctrl & 0x03)
        self._write_reg(self._REG_CONTROL, ctrl)
        self._write_reg(self._REG_PPULSE, ((pplen & 0x03) << 6) | (ppulse & 0x3F))

    def set_led_boost(self, boost):
        """Set additional LED current boost.

        Args:
            boost: LED_BOOST 0-3 (0=100%, 1=150%, 2=200%, 3=300%).
        """
        c2 = self._read_reg(self._REG_CONFIG2)
        c2 = (c2 & 0xCF) | ((boost & 0x03) << 4) | 0x01
        self._write_reg(self._REG_CONFIG2, c2)

    def als_threshold(self, low, high):
        """Set ALS interrupt thresholds.

        Args:
            low: Low threshold 0-65535.
            high: High threshold 0-65535.
        """
        self._write_reg(self._REG_AILTL, low & 0xFF)
        self._write_reg(self._REG_AILTH, (low >> 8) & 0xFF)
        self._write_reg(self._REG_AIHTL, high & 0xFF)
        self._write_reg(self._REG_AIHTH, (high >> 8) & 0xFF)

    def proximity_threshold(self, low, high):
        """Set proximity interrupt thresholds.

        Args:
            low: Low threshold 0-255.
            high: High threshold 0-255.
        """
        self._write_reg(self._REG_PILT, low & 0xFF)
        self._write_reg(self._REG_PIHT, high & 0xFF)

    def set_persistence(self, ppers, apers):
        """Set interrupt persistence filters.

        Args:
            ppers: Proximity persistence 0-15 (0=every cycle, 1-15=N consecutive).
            apers: ALS persistence 0-15.
        """
        self._write_reg(self._REG_PERS, ((ppers & 0x0F) << 4) | (apers & 0x0F))

    def enable_als_interrupt(self, enabled):
        """Enable or disable ALS interrupt.

        Args:
            enabled: True to enable AIEN, False to disable.
        """
        val = self._read_reg(self._REG_ENABLE)
        if enabled:
            val |= 0x10
        else:
            val &= ~0x10
        self._write_reg(self._REG_ENABLE, val)

    def enable_proximity_interrupt(self, enabled):
        """Enable or disable proximity interrupt.

        Args:
            enabled: True to enable PIEN, False to disable.
        """
        val = self._read_reg(self._REG_ENABLE)
        if enabled:
            val |= 0x20
        else:
            val &= ~0x20
        self._write_reg(self._REG_ENABLE, val)

    def clear_proximity_interrupt(self):
        """Clear the proximity interrupt via address-only write to PICLEAR (0xE5)."""
        self._transport.write(bytes([self._REG_PICLEAR]))

    def clear_als_interrupt(self):
        """Clear the ALS/color interrupt via address-only write to CICLEAR (0xE6)."""
        self._transport.write(bytes([self._REG_CICLEAR]))

    def clear_all_interrupts(self):
        """Clear all non-gesture interrupts via address-only write to AICLEAR (0xE7)."""
        self._transport.write(bytes([self._REG_AICLEAR]))

    def set_proximity_offset(self, ur, dl):
        """Set proximity offset for UP/RIGHT and DOWN/LEFT photodiodes.

        Uses sign-magnitude encoding: bit 7=sign (1=negative), bits 6:0=magnitude.

        Args:
            ur: UP/RIGHT offset -127 to +127.
            dl: DOWN/LEFT offset -127 to +127.
        """
        self._write_reg(self._REG_POFFSET_UR, self._encode_offset(ur))
        self._write_reg(self._REG_POFFSET_DL, self._encode_offset(dl))

    def _encode_offset(self, value):
        if value < 0:
            return 0x80 | (abs(value) & 0x7F)
        return value & 0x7F

    def set_proximity_mask(self, u, d, l, r):
        """Mask individual photodiodes in proximity detection.

        Args:
            u: True to mask UP photodiode.
            d: True to mask DOWN photodiode.
            l: True to mask LEFT photodiode.
            r: True to mask RIGHT photodiode.
        """
        c3 = self._read_reg(self._REG_CONFIG3)
        c3 = (c3 & 0xF0)
        if u:
            c3 |= 0x08
        if d:
            c3 |= 0x04
        if l:
            c3 |= 0x02
        if r:
            c3 |= 0x01
        self._write_reg(self._REG_CONFIG3, c3)

    def enable_gesture(self, enabled):
        """Enable or disable the gesture engine.

        Args:
            enabled: True to enable GEN and set GMODE, False to disable.
        """
        val = self._read_reg(self._REG_ENABLE)
        if enabled:
            val |= 0x40
            self._write_reg(self._REG_ENABLE, val)
            g4 = self._read_reg(self._REG_GCONF4)
            g4 |= 0x01
            self._write_reg(self._REG_GCONF4, g4)
        else:
            val &= ~0x40
            self._write_reg(self._REG_ENABLE, val)
            g4 = self._read_reg(self._REG_GCONF4)
            g4 &= ~0x01
            self._write_reg(self._REG_GCONF4, g4)

    def configure_gesture(self, ggain, gldrive, gpulse, gplen, gwtime, gpenth, gexth):
        """Configure gesture engine parameters.

        Args:
            ggain: Gesture gain 0-3 (0=1x, 1=2x, 2=4x, 3=8x).
            gldrive: Gesture LED drive 0-3 (same as LDRIVE).
            gpulse: Gesture pulse count minus 1, 0-63.
            gplen: Gesture pulse length 0-3 (0=4us, 1=8us, 2=16us, 3=32us).
            gwtime: Gesture wait time 0-7.
            gpenth: Gesture proximity entry threshold 0-255.
            gexth: Gesture exit threshold 0-255.
        """
        self._write_reg(self._REG_GPENTH, gpenth & 0xFF)
        self._write_reg(self._REG_GEXTH, gexth & 0xFF)
        g2 = ((ggain & 0x03) << 5) | ((gldrive & 0x03) << 3) | (gwtime & 0x07)
        self._write_reg(self._REG_GCONF2, g2)
        self._write_reg(self._REG_GPULSE, ((gplen & 0x03) << 6) | (gpulse & 0x3F))

    def gesture_available(self):
        """Check if gesture data is available in the FIFO.

        Returns:
            bool: True if GSTATUS.GVALID is set (at least one dataset in FIFO).
        """
        return bool(self._read_reg(self._REG_GSTATUS) & 0x01)

    def read_gesture_fifo(self):
        """Read all gesture datasets from the FIFO.

        Returns:
            list: List of (U, D, L, R) tuples, one per dataset.
        """
        level = self._read_reg(self._REG_GFLVL)
        if level == 0:
            return []
        result = []
        for _ in range(level):
            raw = self._transport.write_read(bytes([self._REG_GFIFO_U]), 4)
            result.append((raw[0], raw[1], raw[2], raw[3]))
        return result

    def gesture_fifo_level(self):
        """Read the number of datasets in the gesture FIFO.

        Returns:
            int: Number of 4-byte datasets currently in FIFO.
        """
        return self._read_reg(self._REG_GFLVL)

    def clear_gesture_fifo(self):
        """Clear the gesture FIFO by setting GFIFO_CLR in GCONF4."""
        g4 = self._read_reg(self._REG_GCONF4)
        g4 |= 0x04
        self._write_reg(self._REG_GCONF4, g4)

    def enable_gesture_interrupt(self, enabled):
        """Enable or disable gesture interrupt.

        Args:
            enabled: True to enable GIEN, False to disable.
        """
        g4 = self._read_reg(self._REG_GCONF4)
        if enabled:
            g4 |= 0x02
        else:
            g4 &= ~0x02
        self._write_reg(self._REG_GCONF4, g4)

    def status(self):
        """Read the raw STATUS register.

        Returns:
            int: Raw STATUS byte.
        """
        return self._read_reg(self._REG_STATUS)

    def is_als_valid(self):
        """Check if ALS/color data is valid.

        Returns:
            bool: True if STATUS.AVALID is set.
        """
        return bool(self._read_reg(self._REG_STATUS) & 0x01)

    def is_proximity_valid(self):
        """Check if proximity data is valid.

        Returns:
            bool: True if STATUS.PVALID is set.
        """
        return bool(self._read_reg(self._REG_STATUS) & 0x02)

    def is_als_saturated(self):
        """Check if the clear photodiode is saturated.

        Returns:
            bool: True if STATUS.CPSAT is set.
        """
        return bool(self._read_reg(self._REG_STATUS) & 0x80)

    def is_proximity_saturated(self):
        """Check if analog saturation occurred during proximity.

        Returns:
            bool: True if STATUS.PGSAT is set.
        """
        return bool(self._read_reg(self._REG_STATUS) & 0x40)

    def chip_id(self):
        """Read the device ID register.

        Returns:
            int: ID register value (expect 0xAB).
        """
        return self._read_reg(self._REG_ID)
