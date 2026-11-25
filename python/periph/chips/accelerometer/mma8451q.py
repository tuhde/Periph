"""MMA8451Q — 3-axis MEMS accelerometer (NXP / Freescale).

Triaxial low-g accelerometer with 14-bit digital output and ±2/±4/±8 *g*
selectable full-scale range. Offloads the host with a 32-sample FIFO
(disabled / circular / fill / trigger), a high-pass-filtered output option,
and five embedded detectors: freefall / motion, transient (high-pass
filtered motion), single / double / directional tap ("pulse"), portrait /
landscape + back / front orientation, and Auto-SLEEP / Auto-WAKE rate
switching. Seven interrupt sources are routable to two pins (INT1, INT2).

Communicates over I²C at address 0x1C (SA0 = GND) or 0x1D (SA0 = VDDIO).
The chip auto-increments the register pointer during a multi-byte read so
the six-byte data burst from 0x01 yields a single coherent (x, y, z)
sample.

The driver is platform-agnostic — it only calls the connection interface.
The same file is used on MicroPython, CircuitPython, and Linux hosts.
"""

from periph.connection.register import to_signed

# Default full-scale range: ±2 g (FS bits 00).
_DEFAULT_RANGE_G = 2
# Default output data rate: 100 Hz (DR bits 011).
_DEFAULT_ODR_HZ = 100
# Default output resolution in bits (14 = normal, 8 = fast read).
_DEFAULT_BITS = 14

# Full-scale range codes in XYZ_DATA_CFG (0x0E) bits 1:0.
_FS_CODES = {2: 0x00, 4: 0x01, 8: 0x02}

# Counts per g at 14-bit resolution, by full-scale range.
_SCALE_14BIT = {2: 4096, 4: 2048, 8: 1024}
# Counts per g at 8-bit (fast read) resolution, by full-scale range.
_SCALE_8BIT = {2: 64, 4: 32, 8: 16}

# Output data rate codes in CTRL_REG1 (0x2A) bits 5:3.
_DR_CODES = {
    800: 0x00,
    400: 0x01,
    200: 0x02,
    100: 0x03,
    50: 0x04,
    12.5: 0x05,
    6.25: 0x06,
    1.56: 0x07,
}

# Auto-SLEEP output rate codes in CTRL_REG1 (0x2A) bits 7:6.
_ASLP_RATE_CODES = {50: 0x00, 12.5: 0x01, 6.25: 0x02, 1.56: 0x03}

# Oversampling mode codes in CTRL_REG2 (0x2B) bits 1:0 (MODS) and 4:3 (SMODS).
# 0 normal, 1 low-noise low-power, 2 high resolution, 3 low power.
_MODS_CODES = {0: 0x00, 1: 0x01, 2: 0x02, 3: 0x03}

# Debounce step in ms, indexed by [ODR index][oversampling mode].
# Rows: 800, 400, 200, 100, 50, 12.5, 6.25, 1.56 Hz.
# Columns: normal, low-noise low-power, high resolution, low power.
_DEBOUNCE_STEPS = (
    (1.25, 1.25, 1.25, 1.25),
    (2.5, 2.5, 2.5, 2.5),
    (5.0, 5.0, 2.5, 5.0),
    (10.0, 10.0, 2.5, 10.0),
    (20.0, 20.0, 2.5, 20.0),
    (20.0, 80.0, 2.5, 80.0),
    (20.0, 80.0, 2.5, 160.0),
    (20.0, 80.0, 2.5, 160.0),
)

# Pulse time-limit step in ms, indexed by [ODR index][oversampling mode].
# With Pulse_LPF_EN = 1 (lpf=True).
_PULSE_TMLT_LPF = (
    (1.25, 1.25, 1.25, 1.25),
    (2.5, 2.5, 2.5, 2.5),
    (5.0, 5.0, 2.5, 5.0),
    (10.0, 10.0, 2.5, 10.0),
    (20.0, 20.0, 2.5, 20.0),
    (20.0, 80.0, 2.5, 80.0),
    (20.0, 80.0, 2.5, 160.0),
    (20.0, 80.0, 2.5, 160.0),
)

# Pulse latency / window step in ms, indexed by [ODR index][oversampling mode].
# With Pulse_LPF_EN = 1 (lpf=True).
_PULSE_LTCY_LPF = (
    (2.5, 2.5, 2.5, 2.5),
    (5.0, 5.0, 5.0, 5.0),
    (10.0, 10.0, 5.0, 10.0),
    (20.0, 20.0, 5.0, 20.0),
    (40.0, 40.0, 5.0, 40.0),
    (40.0, 160.0, 5.0, 160.0),
    (40.0, 160.0, 5.0, 320.0),
    (40.0, 160.0, 5.0, 320.0),
)

# Pulse time-limit step in ms, with Pulse_LPF_EN = 0 (lpf=False).
_PULSE_TMLT_NO_LPF = (
    (0.625, 0.625, 0.625, 0.625),
    (0.625, 0.625, 0.625, 1.25),
    (1.25, 1.25, 0.625, 2.5),
    (2.5, 2.5, 0.625, 5.0),
    (5.0, 5.0, 0.625, 10.0),
    (5.0, 20.0, 0.625, 40.0),
    (5.0, 20.0, 0.625, 40.0),
    (5.0, 20.0, 0.625, 40.0),
)

# Pulse latency / window step in ms, with Pulse_LPF_EN = 0 (lpf=False).
_PULSE_LTCY_NO_LPF = (
    (1.25, 1.25, 1.25, 1.25),
    (1.25, 1.25, 1.25, 2.5),
    (2.5, 2.5, 1.25, 5.0),
    (5.0, 5.0, 1.25, 10.0),
    (10.0, 10.0, 1.25, 20.0),
    (10.0, 40.0, 1.25, 80.0),
    (10.0, 40.0, 1.25, 80.0),
    (10.0, 40.0, 1.25, 80.0),
)

# Ordered list of the supported ODRs — index into the step tables above.
_ODR_ORDER = (800, 400, 200, 100, 50, 12.5, 6.25, 1.56)

# Portrait / landscape trip angles and their P_L_THS<4:0> codes (datasheet
# Table 32). Only these discrete angles are representable.
_ORIENTATION_THRESHOLDS = {
    15: 0x07,
    20: 0x09,
    30: 0x0C,
    35: 0x0D,
    40: 0x0F,
    45: 0x10,
    55: 0x13,
    60: 0x14,
    70: 0x17,
    75: 0x19,
}

# Threshold LSB size in g (7-bit detector thresholds, 0.063 g/LSB).
_THRESHOLD_LSB_G = 0.063
# Offset register LSB size in g (2 mg/LSB).
_OFFSET_LSB_G = 0.002

# Self-test typical count deltas per axis (datasheet Table 2).
_SELF_TEST_TYPICAL = (181, 255, 1680)


def _delay_ms(ms):
    """Sleep for `ms` milliseconds on MicroPython and CPython alike."""
    import time
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


def _clamp(value, low, high):
    """Clamp `value` into the inclusive [low, high] range."""
    if value < low:
        return low
    if value > high:
        return high
    return value


class MMA8451QMinimal:
    """MMA8451Q 3-axis accelerometer — minimal interface.

    Reads X, Y, Z acceleration in *g* with sensible defaults; no
    configuration is required beyond the connection.

    Default configuration (baked in at construction):
        - Range ±2 *g* (4096 counts/g)
        - Output data rate 100 Hz
        - 14-bit output resolution (normal oversampling)
        - Low-noise mode off, high-pass output off
        - FIFO disabled, all detectors and interrupt routing off
        - Interrupts active-low, push-pull; offsets 0

    Args:
        connection: Configured ``RegisterConnection`` (I²C address 0x1C with
            SA0 = GND, or 0x1D with SA0 = VDDIO).
    """

    # Register map.
    _REG_STATUS        = 0x00
    _REG_OUT_X_MSB     = 0x01
    _REG_OUT_X_LSB     = 0x02
    _REG_OUT_Y_MSB     = 0x03
    _REG_OUT_Y_LSB     = 0x04
    _REG_OUT_Z_MSB     = 0x05
    _REG_OUT_Z_LSB     = 0x06
    _REG_F_SETUP       = 0x09
    _REG_TRIG_CFG      = 0x0A
    _REG_SYSMOD        = 0x0B
    _REG_INT_SOURCE    = 0x0C
    _REG_WHO_AM_I      = 0x0D
    _REG_XYZ_DATA_CFG  = 0x0E
    _REG_HP_FILTER     = 0x0F
    _REG_PL_STATUS     = 0x10
    _REG_PL_CFG        = 0x11
    _REG_PL_COUNT      = 0x12
    _REG_PL_BF_ZCOMP   = 0x13
    _REG_P_L_THS       = 0x14
    _REG_FF_MT_CFG     = 0x15
    _REG_FF_MT_SRC     = 0x16
    _REG_FF_MT_THS     = 0x17
    _REG_FF_MT_COUNT   = 0x18
    _REG_TRANSIENT_CFG = 0x1D
    _REG_TRANSIENT_SRC = 0x1E
    _REG_TRANSIENT_THS = 0x1F
    _REG_TRANSIENT_CNT = 0x20
    _REG_PULSE_CFG     = 0x21
    _REG_PULSE_SRC     = 0x22
    _REG_PULSE_THSX    = 0x23
    _REG_PULSE_THSY    = 0x24
    _REG_PULSE_THSZ    = 0x25
    _REG_PULSE_TMLT    = 0x26
    _REG_PULSE_LTCY    = 0x27
    _REG_PULSE_WIND    = 0x28
    _REG_ASLP_COUNT    = 0x29
    _REG_CTRL_REG1     = 0x2A
    _REG_CTRL_REG2     = 0x2B
    _REG_CTRL_REG3     = 0x2C
    _REG_CTRL_REG4     = 0x2D
    _REG_CTRL_REG5     = 0x2E
    _REG_OFF_X         = 0x2F
    _REG_OFF_Y         = 0x30
    _REG_OFF_Z         = 0x31

    # WHO_AM_I (0x0D) identity value.
    _WHO_AM_I_VALUE = 0x1A

    # Interrupt source constants — these are the INT_SOURCE / CTRL_REG4 /
    # CTRL_REG5 bit positions.
    SOURCE_DATA_READY  = 0x01
    SOURCE_FF_MT       = 0x04
    SOURCE_PULSE       = 0x08
    SOURCE_ORIENTATION = 0x10
    SOURCE_TRANSIENT   = 0x20
    SOURCE_FIFO        = 0x40
    SOURCE_AUTO_SLEEP  = 0x80

    # STATUS register bits.
    _STATUS_ZYXDR = 0x08
    _STATUS_ZYXOW = 0x80

    # CTRL_REG1 bit positions.
    _CTRL1_ACTIVE  = 0x01
    _CTRL1_F_READ  = 0x02
    _CTRL1_LNOISE  = 0x04

    # CTRL_REG2 bit positions.
    _CTRL2_RST = 0x40
    _CTRL2_ST  = 0x80

    def __init__(self, connection):
        self._connection = connection
        self._range_g = _DEFAULT_RANGE_G
        self._odr_hz = _DEFAULT_ODR_HZ
        self._bits = _DEFAULT_BITS
        self._low_noise = False
        self._oversampling = 0
        self._sleep_oversampling = 0
        self._fifo_mode = 0
        self.init(connection)

    def init(self, connection=None):
        """Run the chip's initialization sequence.

        Verifies ``WHO_AM_I`` (0x1A), performs a software reset, then
        configures ±2 *g*, 100 Hz, 14-bit output, normal oversampling and
        switches the chip to ACTIVE.

        Args:
            connection: Optional replacement connection; when given, the
                driver is re-bound to it before the sequence runs.

        Raises:
            ValueError: If ``WHO_AM_I`` does not read back 0x1A.
        """
        if connection is not None:
            self._connection = connection
        # 1. Power-up delay (I²C ready ~350 us after VDDIO is on).
        _delay_ms(1)
        # 2. Identity check.
        who = self._read_reg(self._REG_WHO_AM_I)
        if who != self._WHO_AM_I_VALUE:
            raise ValueError('MMA8451Q WHO_AM_I: expected 0x{:02X}, got 0x{:02X}'.format(
                self._WHO_AM_I_VALUE, who))
        # 3. Software reset — the I²C interface is reset by it, so the write
        #    may NACK; wait and re-read CTRL_REG2 until it reads back 0.
        self._write_reg(self._REG_CTRL_REG2, self._CTRL2_RST)
        _delay_ms(1)
        self._wait_reset_done()
        # 4. ±2 g, no high-pass output.
        self._write_reg(self._REG_XYZ_DATA_CFG, 0x00)
        # 5. Normal oversampling in WAKE and SLEEP, Auto-SLEEP off.
        self._write_reg(self._REG_CTRL_REG2, 0x00)
        # 6. 100 Hz, LNOISE off, F_READ off, ACTIVE.
        self._write_reg(self._REG_CTRL_REG1, 0x19)
        # 7. Wait for the STANDBY -> ACTIVE turn-on time (2/ODR + 2 ms).
        self._wait_turn_on()
        self._range_g = _DEFAULT_RANGE_G
        self._odr_hz = _DEFAULT_ODR_HZ
        self._bits = _DEFAULT_BITS
        self._low_noise = False
        self._oversampling = 0
        self._sleep_oversampling = 0
        self._fifo_mode = 0

    def read(self):
        """Read 3-axis linear acceleration.

        Burst-reads the six data bytes (0x01–0x06) so the X, Y, Z samples
        are guaranteed to come from a single measurement. In 8-bit (fast
        read) mode the three MSB registers are read individually instead.

        Returns:
            tuple: (x, y, z) acceleration in *g*.
        """
        if self._bits == 8:
            raw = bytes((self._read_reg(self._REG_OUT_X_MSB),
                         self._read_reg(self._REG_OUT_Y_MSB),
                         self._read_reg(self._REG_OUT_Z_MSB)))
        else:
            raw = self._read_burst(self._REG_OUT_X_MSB, 6)
        return self._decode(raw)

    # ---- internal helpers ------------------------------------------------

    def _write_reg(self, reg, value):
        """Write one byte to `reg`."""
        self._connection.write_reg(reg, value & 0xFF)

    def _read_reg(self, reg):
        """Read one byte from `reg`."""
        return self._connection.read_reg(reg, 1)[0]

    def _read_burst(self, reg, n):
        """Read `n` consecutive bytes starting at `reg`."""
        return self._connection.read_reg(reg, n)

    def _decode(self, raw):
        """Convert raw output bytes to (x, y, z) in *g*.

        In 8-bit (fast read) mode `raw` holds three bytes — X, Y and Z
        MSB; in 14-bit mode it holds the six MSB/LSB bytes.
        """
        if self._bits == 8:
            scale = _SCALE_8BIT[self._range_g]
            cx = to_signed(raw[0], 8)
            cy = to_signed(raw[1], 8)
            cz = to_signed(raw[2], 8)
        else:
            scale = _SCALE_14BIT[self._range_g]
            rx = ((raw[0] << 8) | raw[1])
            ry = ((raw[2] << 8) | raw[3])
            rz = ((raw[4] << 8) | raw[5])
            # 14-bit data is left-justified; bits 1:0 of the LSB byte are 0.
            cx = to_signed(rx >> 2, 14)
            cy = to_signed(ry >> 2, 14)
            cz = to_signed(rz >> 2, 14)
        return (cx / scale, cy / scale, cz / scale)

    def _wait_reset_done(self):
        """Poll CTRL_REG2 until the software reset has completed."""
        waited = 0
        while waited < 20:
            try:
                if self._read_reg(self._REG_CTRL_REG2) == 0x00:
                    return True
            except OSError:
                pass
            _delay_ms(1)
            waited += 1
        return False

    def _turn_on_time_ms(self):
        """Return the STANDBY -> ACTIVE turn-on time in ms (2/ODR + 2)."""
        return (2.0 / self._odr_hz) * 1000.0 + 2.0

    def _wait_turn_on(self):
        """Wait for the STANDBY -> ACTIVE turn-on time."""
        _delay_ms(self._turn_on_time_ms())

    def _standby_write(self, reg, value, ctrl1=None):
        """Write a STANDBY-only register, restoring ACTIVE afterwards.

        Args:
            reg: Register address.
            value: Byte value to write.
            ctrl1: The CTRL_REG1 value to restore. When `reg` is
                CTRL_REG1 itself this is the *new* value (ACTIVE set);
                otherwise it defaults to the current CTRL_REG1 contents.
        """
        self._standby_write_multi(reg, value, None, ctrl1)

    def _standby_write_multi(self, reg, value, extra=None, ctrl1=None):
        """Write a STANDBY-only register plus optional extra registers.

        Args:
            reg: Register address of the primary write.
            value: Byte value for `reg`.
            extra: Optional list of (register, value) pairs written as well.
            ctrl1: The CTRL_REG1 value to restore; defaults to the current
                CTRL_REG1 contents.
        """
        current = self._read_reg(self._REG_CTRL_REG1)
        was_active = bool(current & self._CTRL1_ACTIVE)
        if was_active:
            self._write_reg(self._REG_CTRL_REG1, current & ~self._CTRL1_ACTIVE)
        self._write_reg(reg, value)
        if extra:
            for extra_reg, extra_value in extra:
                self._write_reg(extra_reg, extra_value)
        if was_active:
            restore = current if ctrl1 is None else ctrl1
            self._write_reg(self._REG_CTRL_REG1, restore | self._CTRL1_ACTIVE)
            self._wait_turn_on()


class MMA8451QFull(MMA8451QMinimal):
    """MMA8451Q full interface — extends MMA8451QMinimal with the complete
    chip functionality.

    Adds:
        - Full-scale range (±2/±4/±8 *g*), output data rate (1.56 Hz –
          800 Hz), oversampling mode, low-noise mode and 14-/8-bit
          resolution selection.
        - STANDBY / ACTIVE control, software reset and electrostatic
          self-test.
        - Raw count reading, status and system-mode readback.
        - High-pass-filtered output and per-axis offset correction.
        - 32-sample FIFO with watermark, trigger sources and gate.
        - Freefall / motion, transient, tap and orientation detectors.
        - Auto-SLEEP / Auto-WAKE with selectable sleep rate and wake
          sources.
        - Interrupt enable / disable, routing, polarity and drive control,
          plus edge subscription.
    """

    def read(self):
        """Read 3-axis linear acceleration in *g*.

        Delegates to :meth:`MMA8451QMinimal.read`.
        """
        return super().read()

    # ---- configuration ----------------------------------------------------

    def set_range(self, range_g):
        """Set the full-scale measurement range.

        Args:
            range_g: One of 2, 4, 8 (g).

        Raises:
            ValueError: If the range is not 2, 4 or 8 *g*, or if ±8 *g* is
                requested while low-noise mode is enabled.
        """
        if range_g not in _FS_CODES:
            raise ValueError('range_g must be one of 2, 4, 8')
        if range_g == 8 and self._low_noise:
            raise ValueError('±8 g is not available in low-noise mode')
        xyz_cfg = self._read_reg(self._REG_XYZ_DATA_CFG)
        xyz_cfg = (xyz_cfg & 0xFC) | _FS_CODES[range_g]
        self._standby_write(self._REG_XYZ_DATA_CFG, xyz_cfg)
        self._range_g = range_g

    def set_data_rate(self, rate_hz):
        """Set the output data rate.

        Args:
            rate_hz: One of 800, 400, 200, 100, 50, 12.5, 6.25, 1.56 (Hz).

        Raises:
            ValueError: If the rate is not supported.
        """
        if rate_hz not in _DR_CODES:
            raise ValueError('rate_hz must be one of 800, 400, 200, 100, 50, 12.5, 6.25, 1.56')
        ctrl = self._read_reg(self._REG_CTRL_REG1)
        ctrl = (ctrl & 0xC7) | (_DR_CODES[rate_hz] << 3)
        self._standby_write(self._REG_CTRL_REG1, ctrl, ctrl1=ctrl)
        self._odr_hz = rate_hz

    def set_oversampling(self, mode, sleep_mode=None):
        """Set the oversampling mode.

        Args:
            mode: 0 normal, 1 low-noise low-power, 2 high resolution,
                3 low power — applies in WAKE / ACTIVE mode.
            sleep_mode: Optional oversampling mode for SLEEP; defaults to
                `mode` when not given.

        Raises:
            ValueError: If the mode is out of range.
        """
        if mode not in _MODS_CODES:
            raise ValueError('mode must be 0, 1, 2 or 3')
        if sleep_mode is None:
            sleep_mode = mode
        if sleep_mode not in _MODS_CODES:
            raise ValueError('sleep_mode must be 0, 1, 2 or 3')
        ctrl2 = self._read_reg(self._REG_CTRL_REG2)
        # Preserve ST / RST / SLPE, replace SMODS (bits 4:3) and MODS (1:0).
        ctrl2 = (ctrl2 & 0xE4) | (_MODS_CODES[sleep_mode] << 3) | _MODS_CODES[mode]
        self._standby_write(self._REG_CTRL_REG2, ctrl2)
        self._oversampling = mode
        self._sleep_oversampling = sleep_mode

    def set_low_noise(self, enabled):
        """Enable or disable the reduced-noise (low-power) mode.

        Low-noise mode limits the dynamic range to ±4 *g*.

        Args:
            enabled: True to enable low-noise mode.

        Raises:
            ValueError: If low-noise mode is requested while the range is
                ±8 *g*.
        """
        if enabled and self._range_g == 8:
            raise ValueError('low-noise mode limits the range to ±4 g')
        ctrl = self._read_reg(self._REG_CTRL_REG1)
        if enabled:
            ctrl |= self._CTRL1_LNOISE
        else:
            ctrl &= ~self._CTRL1_LNOISE
        self._standby_write(self._REG_CTRL_REG1, ctrl, ctrl1=ctrl)
        self._low_noise = bool(enabled)

    def set_resolution(self, bits):
        """Select the output resolution.

        Args:
            bits: 14 for the normal two-byte output, 8 for fast read (MSB
                register only).

        Raises:
            ValueError: If the resolution is not 14 or 8 bits, or if the
                change is attempted while the FIFO is enabled.
        """
        if bits not in (8, 14):
            raise ValueError('bits must be 8 or 14')
        if self._fifo_mode != 0:
            raise ValueError('resolution cannot change while the FIFO is enabled')
        ctrl = self._read_reg(self._REG_CTRL_REG1)
        if bits == 8:
            ctrl |= self._CTRL1_F_READ
        else:
            ctrl &= ~self._CTRL1_F_READ
        self._standby_write(self._REG_CTRL_REG1, ctrl, ctrl1=ctrl)
        self._bits = bits

    def standby(self):
        """Switch the chip to STANDBY mode."""
        ctrl = self._read_reg(self._REG_CTRL_REG1)
        if not (ctrl & self._CTRL1_ACTIVE):
            return
        self._write_reg(self._REG_CTRL_REG1, ctrl & ~self._CTRL1_ACTIVE)

    def activate(self):
        """Switch the chip to ACTIVE mode and wait for the turn-on time."""
        ctrl = self._read_reg(self._REG_CTRL_REG1)
        if ctrl & self._CTRL1_ACTIVE:
            return
        self._write_reg(self._REG_CTRL_REG1, ctrl | self._CTRL1_ACTIVE)
        self._wait_turn_on()

    def reset(self):
        """Perform a software reset.

        All registers return to their reset values and the chip ends up in
        STANDBY. Re-run :meth:`init` afterwards.
        """
        self._write_reg(self._REG_CTRL_REG2, self._CTRL2_RST)
        _delay_ms(1)
        self._wait_reset_done()
        self._range_g = _DEFAULT_RANGE_G
        self._odr_hz = _DEFAULT_ODR_HZ
        self._bits = _DEFAULT_BITS
        self._low_noise = False
        self._oversampling = 0
        self._sleep_oversampling = 0
        self._fifo_mode = 0

    def set_high_pass_output(self, enabled, cutoff=0):
        """Enable the high-pass-filtered output and select its cutoff.

        Args:
            enabled: True to route high-pass-filtered data to the output
                registers and the FIFO.
            cutoff: High-pass cutoff selector 0–3 (the actual frequency
                depends on the ODR and oversampling mode; datasheet
                Table 23).
        """
        if cutoff not in (0, 1, 2, 3):
            raise ValueError('cutoff must be 0, 1, 2 or 3')
        xyz_cfg = self._read_reg(self._REG_XYZ_DATA_CFG)
        if enabled:
            xyz_cfg |= 0x10
        else:
            xyz_cfg &= ~0x10
        hp_filter = self._read_reg(self._REG_HP_FILTER)
        hp_filter = (hp_filter & 0xFC) | cutoff
        self._standby_write_multi(self._REG_XYZ_DATA_CFG, xyz_cfg,
                                  [(self._REG_HP_FILTER, hp_filter)])

    def set_offset(self, x_g, y_g, z_g):
        """Set the per-axis zero-*g* offset correction.

        Args:
            x_g: X offset in *g*.
            y_g: Y offset in *g*.
            z_g: Z offset in *g*.
        """
        ox = self._offset_code(x_g)
        oy = self._offset_code(y_g)
        oz = self._offset_code(z_g)
        self._standby_write_multi(self._REG_OFF_X, ox,
                                  [(self._REG_OFF_Y, oy), (self._REG_OFF_Z, oz)])

    def get_offset(self):
        """Read the per-axis offset correction.

        Returns:
            tuple: (x, y, z) offsets in *g*.
        """
        ox = to_signed(self._read_reg(self._REG_OFF_X), 8)
        oy = to_signed(self._read_reg(self._REG_OFF_Y), 8)
        oz = to_signed(self._read_reg(self._REG_OFF_Z), 8)
        return (ox * _OFFSET_LSB_G, oy * _OFFSET_LSB_G, oz * _OFFSET_LSB_G)

    # ---- status -----------------------------------------------------------

    def read_raw(self):
        """Read raw signed acceleration counts.

        Returns:
            tuple: (x, y, z) signed counts — 14-bit right-aligned, or
            8-bit in fast-read mode.
        """
        if self._bits == 8:
            return (to_signed(self._read_reg(self._REG_OUT_X_MSB), 8),
                    to_signed(self._read_reg(self._REG_OUT_Y_MSB), 8),
                    to_signed(self._read_reg(self._REG_OUT_Z_MSB), 8))
        raw = self._read_burst(self._REG_OUT_X_MSB, 6)
        rx = ((raw[0] << 8) | raw[1]) >> 2
        ry = ((raw[2] << 8) | raw[3]) >> 2
        rz = ((raw[4] << 8) | raw[5]) >> 2
        return (to_signed(rx, 14), to_signed(ry, 14), to_signed(rz, 14))

    def new_data_available(self):
        """Check whether a new sample set is ready.

        Returns:
            bool: True when ``STATUS.ZYXDR`` is set. Not valid while the
            FIFO is enabled — use :meth:`fifo_level` instead.
        """
        status = self._read_reg(self._REG_STATUS)
        return bool(status & self._STATUS_ZYXDR)

    def read_status(self):
        """Read the STATUS register.

        Returns:
            int: STATUS byte (``ZYXOW`` bit 7 … ``XDR`` bit 0).
        """
        return self._read_reg(self._REG_STATUS)

    def read_system_mode(self):
        """Read the current power mode.

        Returns:
            int: ``SYSMOD`` bits 1:0 — 0 standby, 1 wake, 2 sleep.
        """
        return self._read_reg(self._REG_SYSMOD) & 0x03

    # ---- FIFO -------------------------------------------------------------

    def set_fifo(self, mode, watermark=0):
        """Configure the FIFO.

        Args:
            mode: 0 disabled, 1 circular, 2 fill, 3 trigger.
            watermark: FIFO watermark 0–32 (0 disables the watermark flag).
                In trigger mode this is the number of pre-trigger samples.

        Raises:
            ValueError: If the mode is out of range or the watermark is
                outside 0–32.
        """
        if mode not in (0, 1, 2, 3):
            raise ValueError('mode must be 0, 1, 2 or 3')
        if watermark < 0 or watermark > 32:
            raise ValueError('watermark must be 0..32')
        if mode != 0 and self._fifo_mode != 0 and self._fifo_mode != mode:
            # The mode must go through 00 to switch between enabled modes.
            self._write_fifo_setup(0, 0)
        self._write_fifo_setup(mode, watermark)
        self._fifo_mode = mode

    def set_fifo_trigger(self, transient=False, orientation=False, pulse=False,
                         motion=False):
        """Select the FIFO trigger sources.

        Args:
            transient: Enable the transient detector as a trigger source.
            orientation: Enable the orientation detector as a trigger source.
            pulse: Enable the pulse (tap) detector as a trigger source.
            motion: Enable the freefall / motion detector as a trigger source.
        """
        trig = 0
        if transient:
            trig |= 0x20
        if orientation:
            trig |= 0x10
        if pulse:
            trig |= 0x08
        if motion:
            trig |= 0x04
        self._standby_write(self._REG_TRIG_CFG, trig)

    def fifo_level(self):
        """Read the number of samples currently in the FIFO.

        Returns:
            int: Sample count (0–32).
        """
        status = self._read_reg(self._REG_STATUS)
        return status & 0x3F

    def read_fifo_status(self):
        """Read and decode the FIFO status.

        Reading F_STATUS clears the FIFO interrupt source.

        Returns:
            tuple: (overflow, watermark, count) — overflow and watermark are
            booleans, count is the number of samples (0–32).
        """
        status = self._read_reg(self._REG_STATUS)
        return (bool(status & 0x80), bool(status & 0x40), status & 0x3F)

    def read_fifo(self, count=None):
        """Drain samples from the FIFO.

        One sample is read per transaction starting at 0x01 (6 bytes, or 3
        in 8-bit mode).

        Args:
            count: Maximum number of samples to read; ``None`` reads all
                samples currently in the FIFO.

        Returns:
            list: List of (x, y, z) acceleration tuples in *g*.
        """
        samples = []
        if count is None:
            count = self.fifo_level()
        # In 8-bit (fast read) mode the auto-increment skips the LSB
        # registers, so one FIFO sample is three bytes.
        nbytes = 3 if self._bits == 8 else 6
        for _ in range(count):
            raw = self._read_burst(self._REG_OUT_X_MSB, nbytes)
            samples.append(self._decode(raw))
        return samples

    # ---- detectors --------------------------------------------------------

    def set_freefall_motion(self, motion, axes=7, threshold_g=0.0,
                             debounce_ms=0, latch=False, debounce_clear=False):
        """Configure the freefall / motion detector.

        Args:
            motion: True for motion detection (any enabled axis above the
                threshold), False for freefall (all enabled axes below the
                threshold).
            axes: Axis mask — bit 0 = X, bit 1 = Y, bit 2 = Z.
            threshold_g: Threshold in *g* (0.063 *g*/LSB, valid to 8 *g*).
            debounce_ms: Debounce time in ms.
            latch: True to latch the event flags until they are read.
            debounce_clear: True to clear the debounce counter when the
                condition is lost (``DBCNTM``).
        """
        if axes < 0 or axes > 7:
            raise ValueError('axes must be a 3-bit mask (0..7)')
        cfg = 0
        if latch:
            cfg |= 0x80
        if motion:
            cfg |= 0x40
        if axes & 0x04:
            cfg |= 0x20
        if axes & 0x02:
            cfg |= 0x10
        if axes & 0x01:
            cfg |= 0x08
        ths = self._threshold_code(threshold_g)
        if debounce_clear:
            ths |= 0x80
        count = self._debounce_code(debounce_ms)
        self._standby_write_multi(self._REG_FF_MT_CFG, cfg,
                                  [(self._REG_FF_MT_THS, ths),
                                   (self._REG_FF_MT_COUNT, count)])

    def read_motion_source(self):
        """Read the freefall / motion event flags.

        Returns:
            int: ``FF_MT_SRC`` byte — cleared when the events are latched.
        """
        return self._read_reg(self._REG_FF_MT_SRC)

    def set_transient(self, axes=7, threshold_g=0.0, debounce_ms=0,
                      bypass_hpf=False, latch=False):
        """Configure the transient detector.

        Args:
            axes: Axis mask — bit 0 = X, bit 1 = Y, bit 2 = Z.
            threshold_g: Threshold in *g* (0.063 *g*/LSB).
            debounce_ms: Debounce time in ms.
            bypass_hpf: True to bypass the high-pass filter (the detector
                then behaves like motion detection).
            latch: True to latch the event flags until they are read.
        """
        if axes < 0 or axes > 7:
            raise ValueError('axes must be a 3-bit mask (0..7)')
        cfg = 0
        if latch:
            cfg |= 0x10
        if axes & 0x04:
            cfg |= 0x08
        if axes & 0x02:
            cfg |= 0x04
        if axes & 0x01:
            cfg |= 0x02
        if bypass_hpf:
            cfg |= 0x01
        ths = self._threshold_code(threshold_g)
        count = self._debounce_code(debounce_ms)
        self._standby_write_multi(self._REG_TRANSIENT_CFG, cfg,
                                  [(self._REG_TRANSIENT_THS, ths),
                                   (self._REG_TRANSIENT_CNT, count)])

    def read_transient_source(self):
        """Read the transient event flags.

        Reading clears the flags and the interrupt source.

        Returns:
            int: ``TRANSIENT_SRC`` byte.
        """
        return self._read_reg(self._REG_TRANSIENT_SRC)

    def set_pulse(self, single=0, double=0, threshold_g=(0.5, 0.5, 0.5),
                  time_limit_ms=30, latency_ms=100, window_ms=300,
                  latch=False, double_abort=False, lpf=True, hpf=True):
        """Configure the tap ("pulse") detector.

        Args:
            single: Axis mask for single-tap detection (bit 0 = X,
                bit 1 = Y, bit 2 = Z).
            double: Axis mask for double-tap detection.
            threshold_g: Per-axis threshold tuple (x, y, z) in *g*
                (0.063 *g*/LSB).
            time_limit_ms: Pulse time limit in ms.
            latency_ms: Pulse latency in ms.
            window_ms: Second-pulse window in ms.
            latch: True to latch the event flags until they are read.
            double_abort: True to abort double-tap detection when a pulse
                starts during the latency window.
            lpf: True to enable the pulse low-pass filter.
            hpf: True to keep the pulse high-pass filter enabled.
        """
        if single < 0 or single > 7 or double < 0 or double > 7:
            raise ValueError('single and double must be 3-bit axis masks (0..7)')
        cfg = 0
        if double_abort:
            cfg |= 0x80
        if latch:
            cfg |= 0x40
        if double & 0x04:
            cfg |= 0x20
        if double & 0x02:
            cfg |= 0x10
        if double & 0x01:
            cfg |= 0x08
        if single & 0x04:
            cfg |= 0x04
        if single & 0x02:
            cfg |= 0x02
        if single & 0x01:
            cfg |= 0x01
        thsx = self._threshold_code(threshold_g[0])
        thsy = self._threshold_code(threshold_g[1])
        thsz = self._threshold_code(threshold_g[2])
        tmlt = self._pulse_time_code(time_limit_ms, lpf, True)
        ltcy = self._pulse_time_code(latency_ms, lpf, False)
        wind = self._pulse_time_code(window_ms, lpf, False)
        hp_filter = self._read_reg(self._REG_HP_FILTER)
        if hpf:
            hp_filter &= ~0x20
        else:
            hp_filter |= 0x20
        if lpf:
            hp_filter |= 0x10
        else:
            hp_filter &= ~0x10
        self._standby_write_multi(self._REG_PULSE_CFG, cfg,
                                  [(self._REG_PULSE_THSX, thsx),
                                   (self._REG_PULSE_THSY, thsy),
                                   (self._REG_PULSE_THSZ, thsz),
                                   (self._REG_PULSE_TMLT, tmlt),
                                   (self._REG_PULSE_LTCY, ltcy),
                                   (self._REG_PULSE_WIND, wind),
                                   (self._REG_HP_FILTER, hp_filter)])

    def read_pulse_source(self):
        """Read the tap event flags.

        Reading clears the flags and the interrupt source.

        Returns:
            int: ``PULSE_SRC`` byte.
        """
        return self._read_reg(self._REG_PULSE_SRC)

    def set_orientation(self, enabled=True, debounce_ms=0, threshold_deg=45,
                        hysteresis=4, z_lock=4, back_front=1):
        """Configure the portrait / landscape orientation detector.

        Args:
            enabled: True to enable the detector.
            debounce_ms: Debounce time in ms.
            threshold_deg: Trip angle — one of 15, 20, 30, 35, 40, 45,
                55, 60, 70, 75 degrees.
            hysteresis: Hysteresis code 0–7 (adds 0/±4/±7/±11/±14/±17/
                ±21/±24 degrees).
            z_lock: Z-lock angle code 0–7 (14/18/21/25/29/33/37/42
                degrees).
            back_front: Back/front threshold code 0–3.

        Raises:
            ValueError: If the trip angle is not one of the supported
                values, if a code is out of range, or if the register
                constraint ``THS + HYS`` in 1…31 is violated.
        """
        if threshold_deg not in _ORIENTATION_THRESHOLDS:
            raise ValueError('threshold_deg must be one of 15, 20, 30, 35, 40, 45, 55, 60, 70, 75')
        if hysteresis < 0 or hysteresis > 7:
            raise ValueError('hysteresis must be 0..7')
        if z_lock < 0 or z_lock > 7:
            raise ValueError('z_lock must be 0..7')
        if back_front < 0 or back_front > 3:
            raise ValueError('back_front must be 0..3')
        ths = _ORIENTATION_THRESHOLDS[threshold_deg]
        if ths + hysteresis < 1 or ths + hysteresis > 31:
            raise ValueError('threshold_deg + hysteresis must give a register value in 1..31')
        pl_cfg = 0x40 if enabled else 0x00
        pl_ths = (ths << 3) | hysteresis
        pl_bf = ((back_front & 0x03) << 6) | (z_lock & 0x07)
        count = self._debounce_code(debounce_ms)
        self._standby_write_multi(self._REG_PL_CFG, pl_cfg,
                                  [(self._REG_PL_COUNT, count),
                                   (self._REG_P_L_THS, pl_ths),
                                   (self._REG_PL_BF_ZCOMP, pl_bf)])

    def read_orientation(self):
        """Read the orientation status.

        Reading clears the ``NEWLP`` flag.

        Returns:
            tuple: (changed, lockout, portrait_landscape, back) —
            ``portrait_landscape`` is 0 portrait up, 1 portrait down,
            2 landscape right, 3 landscape left; ``back`` is a bool.
        """
        status = self._read_reg(self._REG_PL_STATUS)
        changed = bool(status & 0x80)
        lockout = bool(status & 0x40)
        portrait_landscape = (status >> 1) & 0x03
        back = bool(status & 0x01)
        return (changed, lockout, portrait_landscape, back)

    def set_auto_sleep(self, enabled, sleep_rate_hz=50, timeout_s=10,
                       wake_sources=0):
        """Configure Auto-SLEEP / Auto-WAKE.

        Args:
            enabled: True to enable Auto-SLEEP.
            sleep_rate_hz: Output rate while asleep — 50, 12.5, 6.25 or
                1.56 Hz.
            timeout_s: Inactivity timeout in seconds (0–81 s).
            wake_sources: Mask of wake sources — any combination of
                ``SOURCE_TRANSIENT``, ``SOURCE_ORIENTATION``,
                ``SOURCE_PULSE`` and ``SOURCE_FF_MT``.

        Raises:
            ValueError: If the sleep rate is not supported.
        """
        if sleep_rate_hz not in _ASLP_RATE_CODES:
            raise ValueError('sleep_rate_hz must be one of 50, 12.5, 6.25, 1.56')
        ctrl1 = self._read_reg(self._REG_CTRL_REG1)
        ctrl1 = (ctrl1 & 0x3F) | (_ASLP_RATE_CODES[sleep_rate_hz] << 6)
        ctrl2 = self._read_reg(self._REG_CTRL_REG2)
        if enabled:
            ctrl2 |= 0x04
        else:
            ctrl2 &= ~0x04
        ctrl3 = self._read_reg(self._REG_CTRL_REG3)
        ctrl3 &= 0x07
        if wake_sources & self.SOURCE_TRANSIENT:
            ctrl3 |= 0x40
        if wake_sources & self.SOURCE_ORIENTATION:
            ctrl3 |= 0x20
        if wake_sources & self.SOURCE_PULSE:
            ctrl3 |= 0x10
        if wake_sources & self.SOURCE_FF_MT:
            ctrl3 |= 0x08
        step_s = 0.64 if self._odr_hz <= 1.56 else 0.32
        aslp_count = _clamp(int(round(timeout_s / step_s)), 0, 255)
        self._standby_write_multi(self._REG_CTRL_REG1, ctrl1,
                                  [(self._REG_CTRL_REG2, ctrl2),
                                   (self._REG_CTRL_REG3, ctrl3),
                                   (self._REG_ASLP_COUNT, aslp_count)],
                                  ctrl1=ctrl1)

    def set_fifo_gate(self, enabled):
        """Enable or disable the FIFO gate.

        With the gate enabled the FIFO blocks new input around WAKE / SLEEP
        transitions until it has been emptied.

        Args:
            enabled: True to enable the FIFO gate.
        """
        ctrl3 = self._read_reg(self._REG_CTRL_REG3)
        if enabled:
            ctrl3 |= 0x80
        else:
            ctrl3 &= ~0x80
        self._standby_write(self._REG_CTRL_REG3, ctrl3)

    def set_interrupt_pins(self, active_high=False, open_drain=False):
        """Configure the interrupt pin polarity and drive.

        Both INT pins share these settings.

        Args:
            active_high: True for active-high interrupts (default is
                active-low).
            open_drain: True for open-drain drive (default is push-pull).
        """
        ctrl3 = self._read_reg(self._REG_CTRL_REG3)
        if active_high:
            ctrl3 |= 0x02
        else:
            ctrl3 &= ~0x02
        if open_drain:
            ctrl3 |= 0x01
        else:
            ctrl3 &= ~0x01
        self._standby_write(self._REG_CTRL_REG3, ctrl3)

    # ---- interrupts -------------------------------------------------------

    def enable_interrupt(self, source, pin=1):
        """Enable one interrupt source and route it to INT1 or INT2.

        Args:
            source: One of the ``SOURCE_*`` constants.
            pin: 1 for INT1, 2 for INT2.

        Raises:
            ValueError: If the source or the pin is invalid.
        """
        self._check_source(source)
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        ctrl4 = self._read_reg(self._REG_CTRL_REG4)
        ctrl4 |= source
        ctrl5 = self._read_reg(self._REG_CTRL_REG5)
        if pin == 1:
            ctrl5 |= source
        else:
            ctrl5 &= ~source
        self._standby_write_multi(self._REG_CTRL_REG4, ctrl4,
                                  [(self._REG_CTRL_REG5, ctrl5)])

    def disable_interrupt(self, source):
        """Disable one interrupt source.

        Args:
            source: One of the ``SOURCE_*`` constants.

        Raises:
            ValueError: If the source is invalid.
        """
        self._check_source(source)
        ctrl4 = self._read_reg(self._REG_CTRL_REG4)
        ctrl4 &= ~source
        self._standby_write(self._REG_CTRL_REG4, ctrl4)

    def on_interrupt(self, callback, pin=1):
        """Subscribe to INT line assertions.

        When the connection has no INT pin wired, a 5 ms polling thread is
        started instead.

        Args:
            callback: Function called with the interrupt status byte.
            pin: 1 for INT1, 2 for INT2 (informational — the physical pin
                is selected by the connection's INT pin).
        """
        self._int_callback = callback
        int_pin = getattr(self._connection, 'int_pin', None)
        if int_pin is not None and hasattr(int_pin, 'on_edge'):
            self._int_handler = self._make_int_handler()
            int_pin.on_edge(self._int_handler, getattr(int_pin, 'FALLING', 2))
        else:
            self._start_polling_fallback()

    def off_interrupt(self, pin=1):
        """Unsubscribe from INT line assertions.

        Args:
            pin: 1 for INT1, 2 for INT2 (informational).
        """
        self._int_callback = None
        int_pin = getattr(self._connection, 'int_pin', None)
        handler = getattr(self, '_int_handler', None)
        if int_pin is not None and handler is not None and hasattr(int_pin, 'off_edge'):
            int_pin.off_edge(handler)
            self._int_handler = None
        else:
            stop = getattr(self, '_poll_stop', None)
            if stop is not None:
                stop[0] = True
                self._poll_stop = None

    def poll_interrupt(self):
        """Read the interrupt status and clear the per-source flags.

        Every set bit except ``SOURCE_DATA_READY`` additionally reads the
        matching source register so the flag (and the interrupt pin) is
        cleared. ``SOURCE_DATA_READY`` stays set — clear it by reading the
        data.

        Returns:
            int: ``INT_SOURCE`` byte.
        """
        status = self._read_reg(self._REG_INT_SOURCE)
        if status & self.SOURCE_FIFO:
            self._read_reg(self._REG_STATUS)
        if status & self.SOURCE_FF_MT:
            self._read_reg(self._REG_FF_MT_SRC)
        if status & self.SOURCE_PULSE:
            self._read_reg(self._REG_PULSE_SRC)
        if status & self.SOURCE_ORIENTATION:
            self._read_reg(self._REG_PL_STATUS)
        if status & self.SOURCE_TRANSIENT:
            self._read_reg(self._REG_TRANSIENT_SRC)
        if status & self.SOURCE_AUTO_SLEEP:
            self._read_reg(self._REG_SYSMOD)
        return status

    # ---- self-test --------------------------------------------------------

    def self_test(self):
        """Run the electrostatic self-test.

        The chip is put into STANDBY, set to ±4 *g* at 100 Hz with normal
        oversampling, and the acceleration is sampled before and after the
        self-test force is applied. The previous configuration is restored
        afterwards.

        Returns:
            tuple: (ok, dx, dy, dz) — ``ok`` is True when every axis moved
            by more than half its typical self-test delta, ``dx``/``dy``/
            ``dz`` are the measured count deltas.
        """
        saved_range = self._range_g
        saved_odr = self._odr_hz
        saved_bits = self._bits
        saved_mods = self._oversampling
        saved_smods = self._sleep_oversampling
        saved_lnoise = self._low_noise
        saved_fifo = self._fifo_mode
        try:
            self.set_fifo(0, 0)
            self.standby()
            self.set_range(4)
            self.set_data_rate(100)
            self.set_oversampling(0)
            self.set_resolution(14)
            self.activate()
            before = self._average_counts()
            ctrl2 = self._read_reg(self._REG_CTRL_REG2)
            self._write_reg(self._REG_CTRL_REG2, ctrl2 | self._CTRL2_ST)
            _delay_ms(self._turn_on_time_ms())
            after = self._average_counts()
            self._write_reg(self._REG_CTRL_REG2, ctrl2 & ~self._CTRL2_ST)
        finally:
            # Restore the previous configuration.
            self.standby()
            self.set_resolution(saved_bits if saved_fifo == 0 else 14)
            self.set_oversampling(saved_mods, saved_smods)
            self.set_data_rate(saved_odr)
            self.set_low_noise(saved_lnoise)
            self.set_range(saved_range)
            if saved_fifo:
                self.set_fifo(saved_fifo)
            self.activate()
        dx = after[0] - before[0]
        dy = after[1] - before[1]
        dz = after[2] - before[2]
        ok = (dx > _SELF_TEST_TYPICAL[0] // 2
              and dy > _SELF_TEST_TYPICAL[1] // 2
              and dz > _SELF_TEST_TYPICAL[2] // 2)
        return (ok, dx, dy, dz)

    # ---- internal helpers ------------------------------------------------

    def _average_counts(self):
        """Average a few raw samples and return the mean counts."""
        total = [0, 0, 0]
        for _ in range(4):
            sample = self.read_raw()
            total[0] += sample[0]
            total[1] += sample[1]
            total[2] += sample[2]
        return (total[0] // 4, total[1] // 4, total[2] // 4)

    def _check_source(self, source):
        """Raise ValueError if `source` is not a valid interrupt source."""
        valid = (self.SOURCE_DATA_READY | self.SOURCE_FF_MT | self.SOURCE_PULSE
                 | self.SOURCE_ORIENTATION | self.SOURCE_TRANSIENT
                 | self.SOURCE_FIFO | self.SOURCE_AUTO_SLEEP)
        if not source or (source & ~valid):
            raise ValueError('invalid interrupt source 0x{:02X}'.format(source))

    def _threshold_code(self, threshold_g):
        """Convert a threshold in *g* to the 7-bit register code."""
        return _clamp(int(round(threshold_g / _THRESHOLD_LSB_G)), 0, 127)

    def _offset_code(self, offset_g):
        """Convert an offset in *g* to the signed 8-bit register code."""
        return _clamp(int(round(offset_g / _OFFSET_LSB_G)), -128, 127) & 0xFF

    def _debounce_code(self, debounce_ms):
        """Convert a debounce time in ms to the 8-bit counter code."""
        step = _DEBOUNCE_STEPS[_ODR_ORDER.index(self._odr_hz)][self._oversampling]
        return _clamp(int(round(debounce_ms / step)), 0, 255)

    def _pulse_time_code(self, time_ms, lpf, is_time_limit):
        """Convert a pulse timing in ms to the 8-bit register code."""
        index = _ODR_ORDER.index(self._odr_hz)
        if lpf:
            step = (_PULSE_TMLT_LPF if is_time_limit else _PULSE_LTCY_LPF)[index][self._oversampling]
        else:
            step = (_PULSE_TMLT_NO_LPF if is_time_limit else _PULSE_LTCY_NO_LPF)[index][self._oversampling]
        return _clamp(int(round(time_ms / step)), 0, 255)

    def _write_fifo_setup(self, mode, watermark):
        """Write F_SETUP (mode bits 7:6, watermark bits 5:0)."""
        value = ((mode & 0x03) << 6) | (watermark & 0x3F)
        self._standby_write(self._REG_F_SETUP, value)

    def _make_int_handler(self):
        """Return a zero-argument handler that dispatches to the callback."""

        def _handler():
            self._dispatch_int()

        return _handler

    def _dispatch_int(self):
        """Read the interrupt status and hand it to the callback."""
        callback = getattr(self, '_int_callback', None)
        if callback is None:
            return
        try:
            callback(self.poll_interrupt())
        except Exception:
            pass

    def _start_polling_fallback(self):
        """Start a 5 ms polling thread when no INT pin is wired."""
        stop = [False]
        self._poll_stop = stop
        try:
            import _thread
        except ImportError:
            try:
                import threading
            except ImportError:
                return
            thread = threading.Thread(target=self._poll_loop, args=(stop,))
            thread.daemon = True
            thread.start()
            return
        _thread.start_new_thread(self._poll_loop, (stop,))

    def _poll_loop(self, stop):
        """Poll the interrupt status every 5 ms until `stop` is set."""
        import time
        while not stop[0]:
            self._dispatch_int()
            if hasattr(time, 'sleep_ms'):
                time.sleep_ms(5)
            else:
                time.sleep(0.005)
