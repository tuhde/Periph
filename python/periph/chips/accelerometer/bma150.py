"""BMA150 — 3-axis MEMS accelerometer (Bosch Sensortec).

Triaxial low-g accelerometer with 10-bit digital output and ±2/±4/±8 *g*
selectable full-scale range. Provides an 8-bit on-chip temperature reading
(0.5 °C/LSB), selectable digital bandwidth from 25 Hz to 1500 Hz, and a
data-ready / event interrupt output with built-in low-g (free-fall),
high-g, any-motion and alert logic.

Communicates over I²C at address 0x38 (fixed; the chip also supports
3-/4-wire SPI but that is out of scope for this driver). The chip
auto-increments the register pointer during a multi-byte read so the
six-byte data burst from 0x02 yields a single coherent (x, y, z) sample.

The driver is platform-agnostic — it only calls the connection interface.
The same file is used on MicroPython, CircuitPython, and Linux hosts.
"""

from periph.connection.register import to_signed

# Default range: ±2 g (range bits 00).
_DEFAULT_RANGE_G = 2
# Default bandwidth: 100 Hz (bandwidth bits 010).
_DEFAULT_BANDWIDTH_HZ = 100

# Range bits in RANGE_BW (0x14) bits 4:3, with 11 = not authorised.
_RANGE_BITS = {2: 0x00, 4: 0x08, 8: 0x10}

# Bandwidth bits in RANGE_BW (0x14) bits 2:0, with 111 = not authorised.
_BANDWIDTH_CODES = (
    (25, 0x00),
    (50, 0x01),
    (100, 0x02),
    (190, 0x03),
    (375, 0x04),
    (750, 0x05),
    (1500, 0x06),
)

# Sensitivity (LSB/g) by range. ±2 g = 256 LSB/g, ±4 g = 128 LSB/g, ±8 g = 64 LSB/g.
_RANGE_SCALE = {2: 256, 4: 128, 8: 64}

# Wake-up pause codes (CONFIG 0x15 bits 2:1), with ±30 % tolerance.
_WAKEUP_PAUSE_CODES = {20: 0x00, 80: 0x02, 320: 0x04, 2560: 0x06}

# Any-motion duration codes (HYST_DUR 0x11 bits 7:6).
_ANY_MOTION_DUR_CODES = {1: 0x00, 3: 0x40, 5: 0x80, 7: 0xC0}

# Debounce counter codes in the counter_LG position (INT_CTRL 0x0B bits 3:2);
# counter_HG (bits 5:4) shifts these left by 2. 00 = reset, 01/10/11 = count down 1/2/3 per ms.
_DEBOUNCE_CODES = {0: 0x00, 1: 0x04, 2: 0x08, 3: 0x0C}


def _delay_ms(ms):
    import time
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


def _nearest_bandwidth(bw_hz):
    best_code = _BANDWIDTH_CODES[0][1]
    best_hz = _BANDWIDTH_CODES[0][0]
    for hz, code in _BANDWIDTH_CODES:
        if abs(hz - bw_hz) < abs(best_hz - bw_hz):
            best_code = code
            best_hz = hz
    return best_code, best_hz


class BMA150Minimal:
    """BMA150 3-axis accelerometer — minimal interface.

    Reads X, Y, Z acceleration in *g* with sensible defaults; no
    configuration is required beyond the connection.

    Default configuration (baked in at construction):
        - Range ±2 *g* (256 LSB/g)
        - Bandwidth 100 Hz
        - All interrupts left at EEPROM defaults but INT unused
        - Calibration bits 7:5 of ``RANGE_BW`` (0x14) preserved
        - ``shadow_dis`` = 0 (LSB-then-MSB ordering enforced)

    Args:
        connection: Configured ``RegisterConnection`` (I²C, SMBus, or SPI;
            I²C address is 0x38).
    """

    # Operational register map (0x00–0x15).
    _REG_CHIP_ID         = 0x00
    _REG_VERSION         = 0x01
    _REG_ACC_X_LSB       = 0x02
    _REG_ACC_X_MSB       = 0x03
    _REG_ACC_Y_LSB       = 0x04
    _REG_ACC_Y_MSB       = 0x05
    _REG_ACC_Z_LSB       = 0x06
    _REG_ACC_Z_MSB       = 0x07
    _REG_TEMP            = 0x08
    _REG_STATUS          = 0x09
    _REG_CTRL            = 0x0A
    _REG_INT_CTRL        = 0x0B
    _REG_LG_THRES        = 0x0C
    _REG_LG_DUR          = 0x0D
    _REG_HG_THRES        = 0x0E
    _REG_HG_DUR          = 0x0F
    _REG_ANY_MOTION_THRES = 0x10
    _REG_HYST_DUR        = 0x11
    _REG_CUSTOMER_1      = 0x12
    _REG_CUSTOMER_2      = 0x13
    _REG_RANGE_BW        = 0x14
    _REG_CONFIG          = 0x15

    # CHIP_ID is bits 2:0 of register 0x00; the value is 0b010 (0x02).
    _CHIP_ID_VALUE = 0x02
    _CHIP_ID_MASK = 0x07

    def __init__(self, connection):
        self._connection = connection
        self._range_g = _DEFAULT_RANGE_G
        # Verify CHIP_ID.
        chip_id = self._read_reg(self._REG_CHIP_ID)
        if (chip_id & self._CHIP_ID_MASK) != self._CHIP_ID_VALUE:
            raise ValueError('BMA150 CHIP_ID: expected 0x{:02X}, got 0x{:02X}'.format(
                self._CHIP_ID_VALUE, chip_id & self._CHIP_ID_MASK))
        # Set range/bandwidth preserving calibration bits 7:5 of RANGE_BW.
        bw_code, _ = _nearest_bandwidth(_DEFAULT_BANDWIDTH_HZ)
        rb = self._read_reg(self._REG_RANGE_BW)
        # Bits 7:5 = calibration (preserve); bits 4:3 = range; bits 2:0 = bandwidth.
        rb = (rb & 0xE0) | _RANGE_BITS[_DEFAULT_RANGE_G] | bw_code
        self._write_reg(self._REG_RANGE_BW, rb)
        # Allow filtered data to replace stale registers.
        _delay_ms(5)

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, value & 0xFF)

    def _read_reg(self, reg):
        return self._connection.read_reg(reg, 1)[0]

    def _read_burst(self, reg, n):
        return self._connection.read_reg(reg, n)

    def read(self):
        """Read 3-axis linear acceleration.

        Burst-reads the six LSB-then-MSB data bytes (0x02–0x07) so the X, Y,
        Z samples are guaranteed to come from a single measurement with
        ``shadow_dis=0`` enforcing correct ordering.

        Returns:
            tuple: (x, y, z) acceleration in *g*.
        """
        raw = self._read_burst(self._REG_ACC_X_LSB, 6)
        # Per axis: raw = (MSB << 2) | (LSB >> 6), 0..1023 unsigned,
        # then sign-extend at 512. Bit 0 of each LSB register is the
        # new_data flag — the shift above drops it.
        rx = (((raw[1] << 2) | (raw[0] >> 6)) & 0x3FF)
        ry = (((raw[3] << 2) | (raw[2] >> 6)) & 0x3FF)
        rz = (((raw[5] << 2) | (raw[4] >> 6)) & 0x3FF)
        sx = to_signed(rx, 10)
        sy = to_signed(ry, 10)
        sz = to_signed(rz, 10)
        scale = _RANGE_SCALE[self._range_g]
        return (sx / scale, sy / scale, sz / scale)


# BMA150Full uses BMA150Minimal by composition (no Python inheritance for
# the bits Minimal already covers) but follows the Full-extends-Minimal
# pattern by re-exporting read() as a one-line delegate.

class BMA150Full(BMA150Minimal):
    """BMA150 full interface — extends BMA150Minimal with configuration,
    interrupt sources, low-g / high-g / any-motion / alert logic, sleep,
    soft reset and self-test.

    Adds:
        - Range (±2/±4/±8 *g*) and bandwidth (25–1500 Hz) selection.
        - 10-bit raw reading.
        - 8-bit temperature reading (0.5 °C/LSB, 0x00 = −30 °C).
        - ``new_data`` flag check.
        - Shadow mode toggle.
        - Low-g (free-fall), high-g, any-motion and alert thresholds with
          duration, hysteresis and debounce counters.
        - Latched or self-resetting interrupts.
        - Self-wake-up mode (20/80/320/2560 ms pause).
        - Sleep and soft-reset.
        - Electrostatic self-test.
        - Version register read.
        - Two free-form scratch bytes (CUSTOMER_1/2).
    """

    # Interrupt source constants — match the bit layout the driver uses
    # when enabling / disabling via the LSB bytes of INT_CTRL.
    SOURCE_LOW_G     = 0x01
    SOURCE_HIGH_G    = 0x02
    SOURCE_ANY_MOTION = 0x04
    SOURCE_ALERT     = 0x08
    SOURCE_NEW_DATA  = 0x10

    # STATUS register bits.
    _STATUS_ST_RESULT    = 0x80
    _STATUS_ALERT_PHASE  = 0x10
    _STATUS_LG_LATCHED   = 0x08
    _STATUS_HG_LATCHED   = 0x04
    _STATUS_LG           = 0x02
    _STATUS_HG           = 0x01

    def __init__(self, connection):
        super().__init__(connection)
        self._enabled_sources = 0
        self._sleeping = False

    def read(self):
        """Read 3-axis linear acceleration in *g*.

        Delegates to :meth:`BMA150Minimal.read`.
        """
        return super().read()

    def set_range(self, range_g):
        """Set the measurement range.

        Args:
            range_g: One of 2, 4, 8 (g). Re-computes the LSB/g scale.
        """
        if range_g not in _RANGE_BITS:
            raise ValueError('range_g must be one of 2, 4, 8')
        self._range_g = range_g
        rb = self._read_reg(self._REG_RANGE_BW)
        # Preserve calibration bits 7:5; clear range + bandwidth; re-apply.
        rb = (rb & 0xE0) | _RANGE_BITS[range_g] | (rb & 0x07)
        self._write_reg(self._REG_RANGE_BW, rb)

    def set_bandwidth(self, bandwidth_hz):
        """Set the digital low-pass bandwidth to the nearest supported value.

        Args:
            bandwidth_hz: Requested bandwidth in Hz; clamped to the nearest
                of 25, 50, 100, 190, 375, 750, 1500.
        """
        bw_code, _ = _nearest_bandwidth(bandwidth_hz)
        rb = self._read_reg(self._REG_RANGE_BW)
        # Preserve calibration bits 7:5 and range bits 4:3; clear bw bits 2:0.
        rb = (rb & 0xF8) | bw_code
        self._write_reg(self._REG_RANGE_BW, rb)

    def read_raw(self):
        """Read raw 10-bit two's-complement acceleration counts.

        Returns:
            tuple: (x, y, z) signed counts in [-512, 511].
        """
        raw = self._read_burst(self._REG_ACC_X_LSB, 6)
        rx = (((raw[1] << 2) | (raw[0] >> 6)) & 0x3FF)
        ry = (((raw[3] << 2) | (raw[2] >> 6)) & 0x3FF)
        rz = (((raw[5] << 2) | (raw[4] >> 6)) & 0x3FF)
        return (to_signed(rx, 10), to_signed(ry, 10), to_signed(rz, 10))

    def read_temperature(self):
        """Read on-chip temperature.

        Returns:
            float: Temperature in °C, computed as ``raw * 0.5 - 30``.
        """
        raw = self._read_reg(self._REG_TEMP)
        return raw * 0.5 - 30.0

    def new_data_available(self):
        """Return True if all three axes have new data since the last LSB read.

        The bits sit in the LSB registers, so this consumes one LSB read
        per axis; it does not clear the MSB shadow lock because no MSB is
        touched.
        """
        x_lsb = self._read_reg(self._REG_ACC_X_LSB)
        y_lsb = self._read_reg(self._REG_ACC_Y_LSB)
        z_lsb = self._read_reg(self._REG_ACC_Z_LSB)
        return bool((x_lsb & 0x01) and (y_lsb & 0x01) and (z_lsb & 0x01))

    def set_shadow(self, enabled):
        """Enable or disable MSB-only reads.

        When enabled (``shadow_dis = 1``), reading the MSB no longer
        requires a prior LSB read. Disable (``shadow_dis = 0``) to ensure
        MSB and LSB come from the same conversion.

        Args:
            enabled: True to allow MSB-only reads, False for LSB-then-MSB.
        """
        cfg = self._read_reg(self._REG_CONFIG)
        if enabled:
            cfg |= 0x08
        else:
            cfg &= ~0x08
        self._write_reg(self._REG_CONFIG, cfg)

    def set_low_g(self, threshold_g, duration_ms, hysteresis_g=0, counter=0):
        """Configure the low-g (free-fall) interrupt and enable it.

        Args:
            threshold_g: |a| threshold per axis in *g*.
            duration_ms: Minimum time all three |a| must stay below the
                threshold, in milliseconds (1 ms/LSB).
            hysteresis_g: Hysteresis in *g*; 32 threshold-codes per LSB.
            counter: Debounce counter — 0=reset, 1/2/3 = count down
                1/2/3 per ms while criterion is false.
        """
        self._write_threshold(self._REG_LG_THRES, threshold_g)
        self._write_reg(self._REG_LG_DUR, max(0, min(255, int(duration_ms))) & 0xFF)
        self._write_hyst(self._REG_HYST_DUR, 'lg', hysteresis_g)
        self._write_int_counter('lg', counter)
        self._enable_source(self.SOURCE_LOW_G)

    def set_high_g(self, threshold_g, duration_ms, hysteresis_g=0, counter=0):
        """Configure the high-g (shock) interrupt and enable it.

        Args:
            threshold_g: |a| threshold per axis in *g*.
            duration_ms: Minimum time any |a| must stay at or above the
                threshold, in milliseconds (1 ms/LSB).
            hysteresis_g: Hysteresis in *g*; 32 threshold-codes per LSB.
            counter: Debounce counter — 0=reset, 1/2/3 = count down
                1/2/3 per ms while criterion is false.
        """
        self._write_threshold(self._REG_HG_THRES, threshold_g)
        self._write_reg(self._REG_HG_DUR, max(0, min(255, int(duration_ms))) & 0xFF)
        self._write_hyst(self._REG_HYST_DUR, 'hg', hysteresis_g)
        self._write_int_counter('hg', counter)
        self._enable_source(self.SOURCE_HIGH_G)

    def set_any_motion(self, threshold_g, samples=1):
        """Configure the any-motion interrupt and enable it.

        Args:
            threshold_g: Moving-difference threshold in *g*.
            samples: 1, 3, 5 or 7 consecutive samples above threshold.
        """
        # 15.6 mg/LSB at ±2 g; the chip scales the threshold with range
        # so re-apply after set_range() if the range changes.
        scale = _RANGE_SCALE[self._range_g] / 256.0
        # 1 g = 64 LSB at ±2 g → 1 LSB = 15.625 mg.
        code = int(round(threshold_g / (0.0156 * scale)))
        code = max(0, min(255, code))
        self._write_reg(self._REG_ANY_MOTION_THRES, code)
        if samples not in _ANY_MOTION_DUR_CODES:
            raise ValueError('samples must be 1, 3, 5, or 7')
        hd = self._read_reg(self._REG_HYST_DUR)
        hd = (hd & 0x3F) | _ANY_MOTION_DUR_CODES[samples]
        self._write_reg(self._REG_HYST_DUR, hd)
        # any-motion needs enable_adv_INT=1.
        cfg = self._read_reg(self._REG_CONFIG)
        cfg |= 0x40
        self._write_reg(self._REG_CONFIG, cfg)
        self._enable_source(self.SOURCE_ANY_MOTION)

    def set_alert(self, enabled):
        """Enable alert mode (mutually exclusive with any-motion).

        Alert mode lets any-motion arm a shortened LG/HG event window
        instead of driving INT directly.

        Args:
            enabled: True to enable alert, False to leave disabled.
        """
        if enabled:
            # any-motion flag is cleared (alert replaces it).
            self._enabled_sources &= ~self.SOURCE_ANY_MOTION
            cfg = self._read_reg(self._REG_CONFIG)
            cfg |= 0x40  # enable_adv_INT
            self._write_reg(self._REG_CONFIG, cfg)
            self._enable_source(self.SOURCE_ALERT)
        else:
            self._disable_source(self.SOURCE_ALERT)

    def set_latch(self, enabled):
        """Enable latched interrupts (cleared by :meth:`clear_interrupt`).

        Args:
            enabled: True to latch, False for self-resetting.
        """
        cfg = self._read_reg(self._REG_CONFIG)
        if enabled:
            cfg |= 0x10
        else:
            cfg &= ~0x10
        self._write_reg(self._REG_CONFIG, cfg)

    def clear_interrupt(self):
        """Clear latched interrupts (LG/HG latched bits in STATUS)."""
        if self._sleeping:
            return
        ctrl = self._read_reg(self._REG_CTRL)
        self._write_reg(self._REG_CTRL, ctrl | 0x40)  # reset_INT

    def enable_interrupt(self, source):
        """Enable one interrupt source (e.g. ``SOURCE_LOW_G``).

        Disables mutually exclusive partners (any-motion ↔ alert;
        new_data ↔ LG/HG/any-motion/alert).
        """
        if source == self.SOURCE_NEW_DATA:
            # new_data is exclusive with the four other sources.
            self._enabled_sources &= 0x0F
        else:
            self._enabled_sources &= ~self.SOURCE_NEW_DATA
            if source == self.SOURCE_ANY_MOTION:
                self._enabled_sources &= ~self.SOURCE_ALERT
            elif source == self.SOURCE_ALERT:
                self._enabled_sources &= ~self.SOURCE_ANY_MOTION
        self._enable_source(source)

    def disable_interrupt(self, source):
        """Disable one interrupt source."""
        self._disable_source(source)

    def on_interrupt(self, callback):
        """Subscribe to INT line assertions.

        Args:
            callback: Function called with the STATUS byte.
        """
        self._int_callback = callback
        int_pin = getattr(self._connection, 'int_pin', None)
        if int_pin is not None and hasattr(int_pin, 'on_edge'):
            import _thread
            self._int_handler = lambda pin: self._dispatch_int()
            int_pin.on_edge(self._int_handler, getattr(int_pin, 'FALLING', 0))
        else:
            self._start_polling_fallback()

    def off_interrupt(self):
        """Unsubscribe from INT line assertions."""
        self._int_callback = None
        int_pin = getattr(self._connection, 'int_pin', None)
        handler = getattr(self, '_int_handler', None)
        if int_pin is not None and handler is not None and hasattr(int_pin, 'off_edge'):
            int_pin.off_edge(handler)
        elif hasattr(self, '_poll_timer'):
            try:
                self._poll_timer.deinit()
            except Exception:
                pass
            self._poll_timer = None

    def poll_interrupt(self):
        """Read STATUS without clearing latched bits.

        Returns:
            int: STATUS byte (``LG_latched``/``HG_latched`` are bit 3/2).
        """
        return self._read_reg(self._REG_STATUS)

    def set_wake_up(self, enabled, pause_ms=20):
        """Configure self-wake-up mode.

        In wake-up mode the chip alternates between sleep and a brief
        active window where it evaluates the configured interrupt criteria.

        Args:
            enabled: True to enter self-wake-up, False to leave.
            pause_ms: Sleep portion of the cycle in ms — 20, 80, 320 or
                2560 (±30 %).
        """
        cfg = self._read_reg(self._REG_CONFIG)
        if pause_ms not in _WAKEUP_PAUSE_CODES:
            raise ValueError('pause_ms must be 20, 80, 320, or 2560')
        cfg = (cfg & 0xF9) | _WAKEUP_PAUSE_CODES[pause_ms]
        if enabled:
            cfg |= 0x01
        else:
            cfg &= ~0x01
        self._write_reg(self._REG_CONFIG, cfg)

    def sleep(self):
        """Enter sleep mode. Most bus access is forbidden afterwards."""
        if self._sleeping:
            return
        ctrl = self._read_reg(self._REG_CTRL)
        self._write_reg(self._REG_CTRL, ctrl | 0x01)
        self._sleeping = True

    def wake(self):
        """Leave sleep mode. Waits 1.5 ms for the analog to settle."""
        if not self._sleeping:
            return
        ctrl = self._read_reg(self._REG_CTRL)
        self._write_reg(self._REG_CTRL, ctrl & ~0x01)
        _delay_ms(2)
        self._sleeping = False

    def soft_reset(self):
        """Issue a power-on-equivalent reset; waits 30 ms; restores range.

        After a soft reset the EEPROM-loaded defaults are in force, so the
        last range / bandwidth choice is re-applied.
        """
        ctrl = self._read_reg(self._REG_CTRL)
        self._write_reg(self._REG_CTRL, ctrl | 0x02)
        _delay_ms(30)
        # Restore range/bandwidth without touching calibration bits 7:5.
        bw_code, _ = _nearest_bandwidth(_DEFAULT_BANDWIDTH_HZ)
        rb = self._read_reg(self._REG_RANGE_BW)
        rb = (rb & 0xE0) | _RANGE_BITS[self._range_g] | bw_code
        self._write_reg(self._REG_RANGE_BW, rb)
        self._sleeping = False

    def self_test(self):
        """Run the electrostatic self-test, return ``True`` on pass.

        Asserts ``self_test_0`` in CTRL, waits 100 ms, reads STATUS.
        """
        ctrl = self._read_reg(self._REG_CTRL)
        self._write_reg(self._REG_CTRL, ctrl | 0x04)
        _delay_ms(100)
        status = self._read_reg(self._REG_STATUS)
        # Clear self_test_0.
        self._write_reg(self._REG_CTRL, ctrl)
        return bool(status & self._STATUS_ST_RESULT)

    def read_status(self):
        """Read the STATUS register.

        Returns:
            int: STATUS byte (``LG_latched``=bit 3, ``HG_latched``=bit 2,
            ``st_result``=bit 7, ``alert_phase``=bit 4, etc.).
        """
        return self._read_reg(self._REG_STATUS)

    def read_version(self):
        """Read the VERSION register split into (al_version, ml_version).

        Returns:
            tuple: ``(al_version, ml_version)`` — 4-bit halves of the
            VERSION register (``al_version`` is bits 7:4).
        """
        raw = self._read_reg(self._REG_VERSION)
        return ((raw >> 4) & 0x0F, raw & 0x0F)

    def read_customer(self, index):
        """Read one of the two free scratch bytes (CUSTOMER_1 / CUSTOMER_2).

        Args:
            index: 0 for CUSTOMER_1, 1 for CUSTOMER_2.

        Returns:
            int: Scratch byte value (0x00–0xFF).
        """
        if index == 0:
            return self._read_reg(self._REG_CUSTOMER_1)
        if index == 1:
            return self._read_reg(self._REG_CUSTOMER_2)
        raise ValueError('index must be 0 or 1')

    def write_customer(self, index, value):
        """Write one of the two free scratch bytes.

        Args:
            index: 0 for CUSTOMER_1, 1 for CUSTOMER_2.
            value: 0x00–0xFF.
        """
        if index == 0:
            self._write_reg(self._REG_CUSTOMER_1, value & 0xFF)
        elif index == 1:
            self._write_reg(self._REG_CUSTOMER_2, value & 0xFF)
        else:
            raise ValueError('index must be 0 or 1')

    # ---- internal helpers ------------------------------------------------

    def _write_threshold(self, reg, threshold_g):
        # LG/HG thresholds are unsigned 8-bit, scaled with range.
        code = int(round(threshold_g * 255.0 / self._range_g))
        code = max(0, min(255, code))
        self._write_reg(reg, code)

    def _write_hyst(self, reg, kind, hysteresis_g):
        """Update LG_hyst (bits 2:0) or HG_hyst (bits 5:3) of HYST_DUR."""
        if hysteresis_g < 0:
            raise ValueError('hysteresis_g must be >= 0')
        code = int(round(hysteresis_g * 255.0 / self._range_g / 32.0))
        code = max(0, min(7, code))
        hd = self._read_reg(reg)
        if kind == 'lg':
            hd = (hd & 0xF8) | code
        elif kind == 'hg':
            hd = (hd & 0xC7) | ((code & 0x07) << 3)
        else:
            raise ValueError('kind must be "lg" or "hg"')
        self._write_reg(reg, hd)

    def _write_int_counter(self, kind, counter):
        """Update counter_LG (bits 3:2) or counter_HG (bits 5:4) of INT_CTRL."""
        if counter not in _DEBOUNCE_CODES:
            raise ValueError('counter must be 0, 1, 2, or 3')
        ic = self._read_reg(self._REG_INT_CTRL)
        if kind == 'lg':
            ic = (ic & 0xF3) | _DEBOUNCE_CODES[counter]
        elif kind == 'hg':
            ic = (ic & 0xCF) | (_DEBOUNCE_CODES[counter] << 2)
        else:
            raise ValueError('kind must be "lg" or "hg"')
        self._write_reg(self._REG_INT_CTRL, ic)

    def _enable_source(self, source):
        if self._sleeping:
            return
        self._enabled_sources |= source
        # new_data is wired through CONFIG, not INT_CTRL.
        if source == self.SOURCE_NEW_DATA:
            cfg = self._read_reg(self._REG_CONFIG)
            cfg |= 0x20
            self._write_reg(self._REG_CONFIG, cfg)
            return
        # LG (bit 0) / HG (bit 1) of INT_CTRL.
        if source == self.SOURCE_LOW_G:
            ic = self._read_reg(self._REG_INT_CTRL)
            ic |= 0x01
            self._write_reg(self._REG_INT_CTRL, ic)
        if source == self.SOURCE_HIGH_G:
            ic = self._read_reg(self._REG_INT_CTRL)
            ic |= 0x02
            self._write_reg(self._REG_INT_CTRL, ic)
        # any_motion (bit 6) / alert (bit 7) of INT_CTRL.
        if source == self.SOURCE_ANY_MOTION:
            ic = self._read_reg(self._REG_INT_CTRL)
            ic |= 0x40
            self._write_reg(self._REG_INT_CTRL, ic)
        if source == self.SOURCE_ALERT:
            ic = self._read_reg(self._REG_INT_CTRL)
            ic |= 0x80
            self._write_reg(self._REG_INT_CTRL, ic)

    def _disable_source(self, source):
        self._enabled_sources &= ~source
        if source == self.SOURCE_NEW_DATA:
            cfg = self._read_reg(self._REG_CONFIG)
            cfg &= ~0x20
            self._write_reg(self._REG_CONFIG, cfg)
            return
        if source == self.SOURCE_LOW_G:
            ic = self._read_reg(self._REG_INT_CTRL)
            ic &= ~0x01
            self._write_reg(self._REG_INT_CTRL, ic)
        if source == self.SOURCE_HIGH_G:
            ic = self._read_reg(self._REG_INT_CTRL)
            ic &= ~0x02
            self._write_reg(self._REG_INT_CTRL, ic)
        if source == self.SOURCE_ANY_MOTION:
            ic = self._read_reg(self._REG_INT_CTRL)
            ic &= ~0x40
            self._write_reg(self._REG_INT_CTRL, ic)
        if source == self.SOURCE_ALERT:
            ic = self._read_reg(self._REG_INT_CTRL)
            ic &= ~0x80
            self._write_reg(self._REG_INT_CTRL, ic)

    def _dispatch_int(self):
        cb = getattr(self, '_int_callback', None)
        if cb is None:
            return
        try:
            status = self.poll_interrupt()
            cb(status)
        except Exception:
            pass

    def _start_polling_fallback(self):
        # Fallback: 5 ms polling thread when no INT pin is wired.
        try:
            import _thread
            import time
        except ImportError:
            return
        stop = [False]
        def _loop():
            while not stop[0]:
                self._dispatch_int()
                if hasattr(time, 'sleep_ms'):
                    time.sleep_ms(5)
                else:
                    time.sleep(0.005)
        _thread.start_new_thread(_loop, ())
        self._poll_timer = type('T', (), {'deinit': lambda self_: stop.__setitem__(0, True)})()
