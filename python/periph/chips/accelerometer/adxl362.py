"""ADXL362 — 3-axis MEMS accelerometer (Analog Devices), SPI only.

The ADXL362 is an ultralow power 3-axis accelerometer with 12-bit output
resolution, a 512-sample FIFO, on-chip temperature sensor, autonomous
activity/inactivity (motion) detection with two independent interrupt pins,
and a wake-up mode that consumes ~270 nA. Unlike the ADXL345, it is
SPI-only; there is no I²C mode.

SPI command structure:
    WRITE register   0x0A + addr + data...
    READ  register   0x0B + addr + data...
    READ  FIFO       0x0D       + data...

Register addresses are 6 bits (0x00–0x3F). Burst (multi-byte) transfers
auto-increment the register pointer; the increment halts at the invalid
address 0x3F rather than wrapping. FIFO reads have no address byte and
may be burst-read continuously at the SPI clock rate.

Args:
    connection: Configured SPI connection pointing at the device.
"""


# SPI command bytes (per spec § Transport Configuration / SPI).
_CMD_WRITE_REG = 0x0A
_CMD_READ_REG  = 0x0B
_CMD_READ_FIFO = 0x0D

# Soft-reset key (ASCII 'R').
_SOFT_RESET_KEY = 0x52

# Per-range sensitivity (g/LSB), typical values from the datasheet. The
# ±8 g value is intentionally non-linear (4.255 mg/LSB, not 4× the ±2 g
# scale of 1 mg/LSB) per the datasheet's per-range table.
_SENSITIVITY_G_PER_LSB = (0.001, 0.002, 0.004255)

# Temperature conversion: bias=350 LSB at 25 °C, sensitivity=0.065 °C/LSB.
_TEMP_BIAS_LSB   = 350
_TEMP_BIAS_C     = 25.0
_TEMP_SCALE_C    = 0.065

# ODR codes (FILTER_CTL bits 2:0). '111' (400 Hz) collapses with 0x05–0x07.
_ODR_CODES = (
    (0x00, 12.5),
    (0x01, 25.0),
    (0x02, 50.0),
    (0x03, 100.0),
    (0x04, 200.0),
    (0x05, 400.0),
    (0x06, 400.0),
    (0x07, 400.0),
)

# Range codes (FILTER_CTL bits 7:6). '1X' both map to ±8 g.
_RANGE_CODES = (
    (0x00, 2),
    (0x40, 4),
    (0x80, 8),
    (0xC0, 8),
)


def _delay_ms(ms):
    """Sleep ``ms`` milliseconds, portable across MicroPython and CPython."""
    import time
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(int(ms))
    else:
        time.sleep(ms / 1000.0)


def _sign_extend_12(v):
    """Sign-extend a 12-bit two's-complement value to a signed Python int."""
    v &= 0x0FFF
    if v & 0x0800:
        v -= 0x1000
    return v


def _sensitivity_for_range_bits(bits):
    """Return the typical sensitivity (g/LSB) for the RANGE field (bits 7:6)."""
    bits &= 0xC0
    if bits == 0x00:
        return _SENSITIVITY_G_PER_LSB[0]
    if bits == 0x40:
        return _SENSITIVITY_G_PER_LSB[1]
    return _SENSITIVITY_G_PER_LSB[2]


class ADXL362Minimal:
    """ADXL362 minimal interface — read X, Y, Z acceleration in *g*.

    Performs the chip's full power-up sequence: verifies DEVID_AD=0xAD,
    DEVID_MST=0x1D, and PARTID=0xF2 (the latter is 362 in octal); writes
    the reset FILTER_CTL (RANGE=±2 g, HALF_BW=1, ODR=100 Hz); and switches
    POWER_CTL into measurement mode.

    Default configuration (baked in at construction):
        - ±2 g measurement range
        - 100 Hz output data rate, ODR/4 antialiasing bandwidth
        - Normal noise mode (LOW_NOISE=00)
        - Continuous measurement mode (POWER_CTL.MEASURE=10)
        - FIFO disabled
        - No interrupts mapped; INT1/INT2 high-impedance

    Args:
        connection: Configured SPI connection pointing at the device.
    """

    # Register map (6-bit addresses).
    _REG_DEVID_AD        = 0x00
    _REG_DEVID_MST       = 0x01
    _REG_PARTID          = 0x02
    _REG_XDATA           = 0x08
    _REG_YDATA           = 0x09
    _REG_ZDATA           = 0x0A
    _REG_STATUS          = 0x0B
    _REG_FIFO_ENTRIES_L  = 0x0C
    _REG_FIFO_ENTRIES_H  = 0x0D
    _REG_XDATA_L         = 0x0E
    _REG_XDATA_H         = 0x0F
    _REG_YDATA_L         = 0x10
    _REG_YDATA_H         = 0x11
    _REG_ZDATA_L         = 0x12
    _REG_ZDATA_H         = 0x13
    _REG_TEMP_L          = 0x14
    _REG_TEMP_H          = 0x15
    _REG_SOFT_RESET      = 0x1F
    _REG_THRESH_ACT_L    = 0x20
    _REG_THRESH_ACT_H    = 0x21
    _REG_TIME_ACT        = 0x22
    _REG_THRESH_INACT_L  = 0x23
    _REG_THRESH_INACT_H  = 0x24
    _REG_TIME_INACT_L    = 0x25
    _REG_TIME_INACT_H    = 0x26
    _REG_ACT_INACT_CTL   = 0x27
    _REG_FIFO_CONTROL    = 0x28
    _REG_FIFO_SAMPLES    = 0x29
    _REG_INTMAP1         = 0x2A
    _REG_INTMAP2         = 0x2B
    _REG_FILTER_CTL      = 0x2C
    _REG_POWER_CTL       = 0x2D
    _REG_SELF_TEST       = 0x2E

    # Reset defaults.
    _DEVID_AD_VALUE  = 0xAD
    _DEVID_MST_VALUE = 0x1D
    _PARTID_VALUE    = 0xF2

    # FILTER_CTL reset value: RANGE=±2 g, HALF_BW=1, ODR=100 Hz.
    _FILTER_CTL_DEFAULT = 0x13
    # POWER_CTL measurement-mode value: MEASURE=10, all else 0.
    _POWER_CTL_MEASURE  = 0x02

    # Status bits (1<<position).
    _STATUS_DATA_READY    = 0x01
    _STATUS_FIFO_READY    = 0x02
    _STATUS_FIFO_WATERMARK = 0x04
    _STATUS_FIFO_OVERRUN  = 0x08
    _STATUS_ACT           = 0x10
    _STATUS_INACT         = 0x20
    _STATUS_AWAKE         = 0x40
    _STATUS_ERR_USER_REGS = 0x80

    def __init__(self, connection):
        self._connection = connection
        self._range_bits = 0x00  # ±2 g default
        self._odr_hz = 100.0
        self.init()

    def init(self):
        """Run the chip's full power-up sequence.

        Verifies the triple device-ID read, writes FILTER_CTL and
        POWER_CTL to known-good values, and waits the ODR turnaround
        before returning so the caller can immediately read data.
        """
        # Power-up to standby turn-on time (datasheet: ≤5 ms typical at 100 Hz).
        _delay_ms(5)

        # Burst-read DEVID_AD, DEVID_MST, PARTID and verify the triple.
        ids = self._read_burst(self._REG_DEVID_AD, 3)
        if ids[0] != self._DEVID_AD_VALUE:
            raise ValueError('ADXL362 DEVID_AD: expected 0x{:02X}, got 0x{:02X}'.format(
                self._DEVID_AD_VALUE, ids[0]))
        if ids[1] != self._DEVID_MST_VALUE:
            raise ValueError('ADXL362 DEVID_MST: expected 0x{:02X}, got 0x{:02X}'.format(
                self._DEVID_MST_VALUE, ids[1]))
        if ids[2] != self._PARTID_VALUE:
            raise ValueError('ADXL362 PARTID: expected 0x{:02X}, got 0x{:02X}'.format(
                self._PARTID_VALUE, ids[2]))

        self._write_reg(self._REG_FILTER_CTL, self._FILTER_CTL_DEFAULT)
        self._write_reg(self._REG_POWER_CTL, self._POWER_CTL_MEASURE)

        # Measurement-mode-instruction-to-valid-data: 4/ODR (40 ms at 100 Hz).
        _delay_ms(40)

    def _read_burst(self, reg, n):
        """Read n bytes starting at ``reg`` with auto-incrementing pointer."""
        cmd = bytes([_CMD_READ_REG, reg & 0x3F])
        return self._connection.write_read(cmd, n)

    def _read_fifo(self, n):
        """Read n bytes from the FIFO (no address byte)."""
        return self._connection.write_read(bytes([_CMD_READ_FIFO]), n)

    def _write_reg(self, reg, value):
        """Write a single register."""
        self._connection.write(bytes([_CMD_WRITE_REG, reg & 0x3F, value & 0xFF]))

    def _read_reg(self, reg):
        """Read a single register."""
        return self._read_burst(reg, 1)[0]

    def _read_fifo_entries(self):
        """Return the 10-bit FIFO entry count (0–512)."""
        lo = self._read_reg(self._REG_FIFO_ENTRIES_L)
        hi = self._read_reg(self._REG_FIFO_ENTRIES_H)
        return (lo | ((hi & 0x03) << 8))

    def _threshold_raw(self, threshold_g):
        """Convert an acceleration threshold in *g* to a 10-bit raw value."""
        sens = _sensitivity_for_range_bits(self._range_bits)
        raw = int(round(threshold_g / sens))
        if raw < 0:
            raw = 0
        if raw > 0x3FF:
            raw = 0x3FF
        return raw

    def _time_inact_raw(self, samples):
        """Convert a 16-bit sample count to two FIFO_INACT register bytes."""
        if samples < 0:
            samples = 0
        if samples > 0xFFFF:
            samples = 0xFFFF
        return samples & 0xFF, (samples >> 8) & 0xFF

    def read(self):
        """Read 3-axis linear acceleration.

        Burst-reads the 12-bit XDATA_L/H, YDATA_L/H, ZDATA_L/H register
        sextet so the three samples are guaranteed to come from a single
        measurement.

        Returns:
            tuple: (x, y, z) acceleration in *g*.
        """
        raw = self._read_burst(self._REG_XDATA_L, 6)
        rx = _sign_extend_12((raw[1] & 0x0F) << 8 | raw[0])
        ry = _sign_extend_12((raw[3] & 0x0F) << 8 | raw[2])
        rz = _sign_extend_12((raw[5] & 0x0F) << 8 | raw[4])
        sens = _sensitivity_for_range_bits(self._range_bits)
        return (rx * sens, ry * sens, rz * sens)


class ADXL362Full(ADXL362Minimal):
    """ADXL362 full interface — extends ADXL362Minimal with the full chip API.

    Adds:
        - Device-ID triple read (DEVID_AD/DEVID_MST/PARTID/REVID).
        - Soft-reset (writes 0x52 to SOFT_RESET).
        - Range, ODR, antialiasing, and noise-mode configuration.
        - Wake-up mode (270 nA) and external clock / external sample sync.
        - 8-bit low-resolution read (XDATA/YDATA/ZDATA).
        - On-chip temperature sensor.
        - STATUS accessors (awake, data_ready, FIFO flags).
        - 512-sample FIFO (disabled / oldest-saved / stream / triggered,
          optional temperature interleaving, 9-bit watermark).
        - Activity / inactivity thresholds and timers (referenced or
          absolute, default/linked/loop modes).
        - Per-pin (INT1/INT2) source mapping and active-high/active-low
          polarity.
        - Self-test.

    Args:
        connection: Configured SPI connection pointing at the device.
    """

    # Interrupt source constants (used by set_interrupt).
    SOURCE_DATA_READY    = 0
    SOURCE_FIFO_READY    = 1
    SOURCE_FIFO_WATERMARK = 2
    SOURCE_FIFO_OVERRUN  = 3
    SOURCE_ACT           = 4
    SOURCE_INACT         = 5
    SOURCE_AWAKE         = 6

    # Noise mode constants (POWER_CTL.LOW_NOISE[5:4]).
    NOISE_NORMAL   = 0
    NOISE_LOW      = 1
    NOISE_ULTRALOW = 2

    # Link/loop mode constants (ACT_INACT_CTL.LINKLOOP[5:4]).
    LINKLOOP_DEFAULT = 0
    LINKLOOP_LINKED  = 1
    LINKLOOP_LOOP    = 3

    # FIFO mode constants (FIFO_CONTROL.FIFO_MODE[1:0]).
    FIFO_DISABLED     = 0
    FIFO_OLDEST_SAVED = 1
    FIFO_STREAM       = 2
    FIFO_TRIGGERED    = 3

    # FIFO entry-axis constants (top 2 bits of each 16-bit FIFO entry).
    AXIS_X    = 0
    AXIS_Y    = 1
    AXIS_Z    = 2
    AXIS_TEMP = 3

    # INTMAPx bit layout (mirrors the chip register, single source per bit).
    _INTMAP_DATA_READY    = 0x01
    _INTMAP_FIFO_READY    = 0x02
    _INTMAP_FIFO_WATERMARK = 0x04
    _INTMAP_FIFO_OVERRUN  = 0x08
    _INTMAP_ACT           = 0x10
    _INTMAP_INACT         = 0x20
    _INTMAP_AWAKE         = 0x40
    _INTMAP_INT_LOW       = 0x80

    # ACT_INACT_CTL bit layout.
    _AIC_ACT_EN     = 0x01
    _AIC_ACT_REF    = 0x02
    _AIC_INACT_EN   = 0x04
    _AIC_INACT_REF  = 0x08
    _AIC_LINKLOOP_MASK = 0x30

    # FILTER_CTL bit layout.
    _FILTER_ODR_MASK = 0x07
    _FILTER_EXT_SAMPLE = 0x08
    _FILTER_HALF_BW  = 0x10
    _FILTER_RANGE_MASK = 0xC0

    # POWER_CTL bit layout.
    _POWER_MEASURE_MASK = 0x03
    _POWER_AUTOSLEEP    = 0x04
    _POWER_WAKEUP       = 0x08
    _POWER_LOW_NOISE_MASK = 0x30
    _POWER_EXT_CLK      = 0x40

    def __init__(self, connection):
        super().__init__(connection)

    def device_id(self):
        """Return raw device-ID bytes.

        Returns:
            tuple: (DEVID_AD, DEVID_MST, PARTID, REVID).
        """
        ids = self._read_burst(self._REG_DEVID_AD, 4)
        return (ids[0], ids[1], ids[2], ids[3])

    def soft_reset(self):
        """Soft-reset the chip.

        Writes 0x52 (ASCII 'R') to SOFT_RESET and waits the documented
        0.5 ms latency. All registers return to reset defaults; caller
        must re-run init() before continuing.
        """
        self._write_reg(self._REG_SOFT_RESET, _SOFT_RESET_KEY)
        _delay_ms(1)  # ≥0.5 ms per datasheet
        # Reset clears cached state but doesn't reset cached Python state.
        self._range_bits = 0x00
        self._odr_hz = 100.0

    def set_range(self, range_g):
        """Set the measurement range.

        Args:
            range_g: 2, 4, or 8 (g).
        """
        code = {2: 0x00, 4: 0x40, 8: 0x80}.get(range_g)
        if code is None:
            raise ValueError('range_g must be one of 2, 4, 8')
        f = self._read_reg(self._REG_FILTER_CTL)
        f = (f & ~self._FILTER_RANGE_MASK) | (code & self._FILTER_RANGE_MASK)
        self._write_reg(self._REG_FILTER_CTL, f)
        self._range_bits = code
        # Spec datasheet says no separate delay is required, but the ODR
        # turnaround is observed in continuous mode; wait 1/ODR for safety.
        if self._odr_hz > 0:
            _delay_ms(1000.0 / self._odr_hz + 1)

    def set_odr(self, odr_hz):
        """Set the output data rate to the nearest supported value (12.5–400 Hz).

        Args:
            odr_hz: Requested ODR in Hz; clamped to the nearest of
                12.5, 25, 50, 100, 200, or 400 Hz.
        """
        best_code = _ODR_CODES[0][0]
        best_rate = _ODR_CODES[0][1]
        best_diff = abs(best_rate - odr_hz)
        for code, actual in _ODR_CODES:
            d = abs(actual - odr_hz)
            if d < best_diff:
                best_code, best_rate, best_diff = code, actual, d
        f = self._read_reg(self._REG_FILTER_CTL)
        f = (f & ~self._FILTER_ODR_MASK) | (best_code & self._FILTER_ODR_MASK)
        self._write_reg(self._REG_FILTER_CTL, f)
        self._odr_hz = best_rate

    def set_half_bandwidth(self, enabled):
        """Set FILTER_CTL.HALF_BW (antialiasing bandwidth = ODR/4 or ODR/2)."""
        f = self._read_reg(self._REG_FILTER_CTL)
        if enabled:
            f |= self._FILTER_HALF_BW
        else:
            f &= ~self._FILTER_HALF_BW
        self._write_reg(self._REG_FILTER_CTL, f)

    def set_noise_mode(self, mode):
        """Set POWER_CTL.LOW_NOISE (0=normal, 1=low, 2=ultralow noise)."""
        if mode not in (self.NOISE_NORMAL, self.NOISE_LOW, self.NOISE_ULTRALOW):
            raise ValueError('mode must be NOISE_NORMAL, NOISE_LOW, or NOISE_ULTRALOW')
        p = self._read_reg(self._REG_POWER_CTL)
        p = (p & ~self._POWER_LOW_NOISE_MASK) | ((mode << 4) & self._POWER_LOW_NOISE_MASK)
        self._write_reg(self._REG_POWER_CTL, p)

    def set_wakeup_mode(self, enabled):
        """Enter wake-up mode (270 nA); ignored unless loop mode is engaged."""
        p = self._read_reg(self._REG_POWER_CTL)
        if enabled:
            p |= self._POWER_WAKEUP
        else:
            p &= ~self._POWER_WAKEUP
        self._write_reg(self._REG_POWER_CTL, p)

    def set_autosleep(self, enabled):
        """Set POWER_CTL.AUTOSLEEP; effective only in linked/loop mode."""
        p = self._read_reg(self._REG_POWER_CTL)
        if enabled:
            p |= self._POWER_AUTOSLEEP
        else:
            p &= ~self._POWER_AUTOSLEEP
        self._write_reg(self._REG_POWER_CTL, p)

    def set_external_clock(self, enabled):
        """Set POWER_CTL.EXT_CLK; INT1 is repurposed as clock input."""
        p = self._read_reg(self._REG_POWER_CTL)
        if enabled:
            p |= self._POWER_EXT_CLK
        else:
            p &= ~self._POWER_EXT_CLK
        self._write_reg(self._REG_POWER_CTL, p)

    def set_external_sample_trigger(self, enabled):
        """Set FILTER_CTL.EXT_SAMPLE; INT2 is repurposed as sync trigger input."""
        f = self._read_reg(self._REG_FILTER_CTL)
        if enabled:
            f |= self._FILTER_EXT_SAMPLE
        else:
            f &= ~self._FILTER_EXT_SAMPLE
        self._write_reg(self._REG_FILTER_CTL, f)

    def read_8bit(self):
        """Read 3-axis acceleration using the 8-bit XDATA/YDATA/ZDATA registers.

        Returns:
            tuple: (x, y, z) acceleration in *g*, ~16-LSB resolution.
        """
        raw = self._read_burst(self._REG_XDATA, 3)
        # XDATA/YDATA/ZDATA are signed 8-bit two's complement; an LSB here
        # represents 16 codes of the 12-bit scale.
        def s8(v):
            v &= 0xFF
            return v - 0x100 if v & 0x80 else v
        sens = _sensitivity_for_range_bits(self._range_bits) * 16
        return (s8(raw[0]) * sens, s8(raw[1]) * sens, s8(raw[2]) * sens)

    def temperature(self):
        """Read the on-chip temperature sensor.

        Uses the typical bias (350 LSB @ 25 °C) and sensitivity
        (0.065 °C/LSB) from the datasheet; not part-calibrated.

        Returns:
            float: Temperature in °C.
        """
        raw = self._read_burst(self._REG_TEMP_L, 2)
        raw12 = _sign_extend_12((raw[1] & 0x0F) << 8 | raw[0])
        return _TEMP_BIAS_C + (raw12 - _TEMP_BIAS_LSB) * _TEMP_SCALE_C

    def status(self):
        """Return the raw STATUS register byte."""
        return self._read_reg(self._REG_STATUS)

    def awake(self):
        """Return STATUS.AWAKE."""
        return bool(self.status() & self._STATUS_AWAKE)

    def data_ready(self):
        """Return STATUS.DATA_READY (cleared by data-register or FIFO read)."""
        return bool(self.status() & self._STATUS_DATA_READY)

    def fifo_entries(self):
        """Return the 10-bit FIFO entry count (0–512)."""
        return self._read_fifo_entries()

    def configure_fifo(self, mode, store_temp=False, watermark=128):
        """Configure the FIFO mode, optional temperature storage, and watermark.

        Args:
            mode: One of FIFO_DISABLED (0), FIFO_OLDEST_SAVED (1),
                FIFO_STREAM (2), FIFO_TRIGGERED (3).
            store_temp: True to interleave temperature samples in the FIFO.
            watermark: 9-bit watermark value (0–511).
        """
        if mode not in (self.FIFO_DISABLED, self.FIFO_OLDEST_SAVED,
                        self.FIFO_STREAM, self.FIFO_TRIGGERED):
            raise ValueError('mode must be 0/1/2/3 (DISABLED/OLDEST_SAVED/STREAM/TRIGGERED)')
        if watermark < 0 or watermark > 0x1FF:
            raise ValueError('watermark must be 0–511')
        fc = (mode & 0x03) | ((watermark >> 8) << 3) | (0x04 if store_temp else 0x00)
        self._write_reg(self._REG_FIFO_CONTROL, fc)
        self._write_reg(self._REG_FIFO_SAMPLES, watermark & 0xFF)

    def read_fifo(self):
        """Read all available FIFO entries.

        Each 16-bit entry's top two bits encode the axis (0=X, 1=Y,
        2=Z, 3=temperature); the low 12 bits are signed axis/temperature
        data, scaled with the current range.

        Returns:
            list[tuple[int, float]]: ``[(axis, value), ...]`` where axis
            is one of AXIS_X/AXIS_Y/AXIS_Z/AXIS_TEMP and value is in
            *g* (axes 0–2) or °C (axis 3).
        """
        n = self._read_fifo_entries()
        if n == 0:
            return []
        # Always read an even byte count — each FIFO entry is 16 bits.
        raw = self._read_fifo(n * 2)
        sens = _sensitivity_for_range_bits(self._range_bits)
        out = []
        for i in range(n):
            lo = raw[2 * i]
            hi = raw[2 * i + 1]
            raw16 = (hi << 8) | lo
            axis = (raw16 >> 14) & 0x03
            raw12 = _sign_extend_12(raw16 & 0x0FFF)
            if axis == self.AXIS_TEMP:
                value = _TEMP_BIAS_C + (raw12 - _TEMP_BIAS_LSB) * _TEMP_SCALE_C
            else:
                value = raw12 * sens
            out.append((axis, value))
        return out

    def set_activity_threshold(self, threshold_g, referenced=False):
        """Set the activity threshold in *g*.

        Args:
            threshold_g: Acceleration magnitude that triggers activity
                detection, in *g*. Clamped to 10-bit range relative to
                the current measurement range.
            referenced: True for referenced (relative-to-orientation-at-
                engagement) detection; False for absolute.
        """
        raw = self._threshold_raw(threshold_g)
        self._write_reg(self._REG_THRESH_ACT_L, raw & 0xFF)
        self._write_reg(self._REG_THRESH_ACT_H, (raw >> 8) & 0x03)
        aic = self._read_reg(self._REG_ACT_INACT_CTL)
        if referenced:
            aic |= self._AIC_ACT_REF
        else:
            aic &= ~self._AIC_ACT_REF
        self._write_reg(self._REG_ACT_INACT_CTL, aic)

    def set_activity_time(self, samples):
        """Set the activity-time filter (0–255 samples).

        Args:
            samples: Number of consecutive over-threshold samples
                required to assert activity (0–255). Ignored in wake-up
                mode, which is single-sample.
        """
        if samples < 0 or samples > 0xFF:
            raise ValueError('samples must be 0–255')
        self._write_reg(self._REG_TIME_ACT, samples & 0xFF)

    def set_inactivity_threshold(self, threshold_g, referenced=False):
        """Set the inactivity threshold in *g*.

        Args:
            threshold_g: Acceleration magnitude below which inactivity
                is asserted, in *g*. Clamped to 10-bit range.
            referenced: True for referenced (relative-to-orientation-at-
                engagement) detection; False for absolute.
        """
        raw = self._threshold_raw(threshold_g)
        self._write_reg(self._REG_THRESH_INACT_L, raw & 0xFF)
        self._write_reg(self._REG_THRESH_INACT_H, (raw >> 8) & 0x03)
        aic = self._read_reg(self._REG_ACT_INACT_CTL)
        if referenced:
            aic |= self._AIC_INACT_REF
        else:
            aic &= ~self._AIC_INACT_REF
        self._write_reg(self._REG_ACT_INACT_CTL, aic)

    def set_inactivity_time(self, samples):
        """Set the inactivity-time filter (0–65535 samples).

        Args:
            samples: Number of consecutive under-threshold samples
                required to assert inactivity (0–65535).
        """
        if samples < 0 or samples > 0xFFFF:
            raise ValueError('samples must be 0–65535')
        lo, hi = self._time_inact_raw(samples)
        self._write_reg(self._REG_TIME_INACT_L, lo)
        self._write_reg(self._REG_TIME_INACT_H, hi)

    def enable_activity_detection(self, enabled):
        """Set ACT_INACT_CTL.ACT_EN."""
        aic = self._read_reg(self._REG_ACT_INACT_CTL)
        if enabled:
            aic |= self._AIC_ACT_EN
        else:
            aic &= ~self._AIC_ACT_EN
        self._write_reg(self._REG_ACT_INACT_CTL, aic)

    def enable_inactivity_detection(self, enabled):
        """Set ACT_INACT_CTL.INACT_EN."""
        aic = self._read_reg(self._REG_ACT_INACT_CTL)
        if enabled:
            aic |= self._AIC_INACT_EN
        else:
            aic &= ~self._AIC_INACT_EN
        self._write_reg(self._REG_ACT_INACT_CTL, aic)

    def set_link_loop_mode(self, mode):
        """Set ACT_INACT_CTL.LINKLOOP[5:4].

        Args:
            mode: LINKLOOP_DEFAULT (0), LINKLOOP_LINKED (1), or
                LINKLOOP_LOOP (3).
        """
        if mode not in (self.LINKLOOP_DEFAULT, self.LINKLOOP_LINKED, self.LINKLOOP_LOOP):
            raise ValueError('mode must be LINKLOOP_DEFAULT/LINKED/LOOP')
        aic = self._read_reg(self._REG_ACT_INACT_CTL)
        aic = (aic & ~self._AIC_LINKLOOP_MASK) | ((mode << 4) & self._AIC_LINKLOOP_MASK)
        self._write_reg(self._REG_ACT_INACT_CTL, aic)

    def _intmap_bit(self, source):
        return {
            self.SOURCE_DATA_READY:    self._INTMAP_DATA_READY,
            self.SOURCE_FIFO_READY:    self._INTMAP_FIFO_READY,
            self.SOURCE_FIFO_WATERMARK: self._INTMAP_FIFO_WATERMARK,
            self.SOURCE_FIFO_OVERRUN:  self._INTMAP_FIFO_OVERRUN,
            self.SOURCE_ACT:           self._INTMAP_ACT,
            self.SOURCE_INACT:         self._INTMAP_INACT,
            self.SOURCE_AWAKE:         self._INTMAP_AWAKE,
        }[source]

    def set_interrupt(self, pin, source, enabled):
        """Set or clear one interrupt source on the named INT pin.

        Args:
            pin: 1 or 2.
            source: One of SOURCE_DATA_READY/SOURCE_FIFO_READY/...
            enabled: True to map, False to clear.
        """
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        reg = self._REG_INTMAP1 if pin == 1 else self._REG_INTMAP2
        cur = self._read_reg(reg)
        bit = self._intmap_bit(source)
        if enabled:
            cur |= bit
        else:
            cur &= ~bit
        self._write_reg(reg, cur)

    def set_interrupt_polarity(self, pin, active_low):
        """Set the active-low polarity for one INT pin.

        Args:
            pin: 1 or 2.
            active_low: True for active-low, False for active-high.
        """
        if pin not in (1, 2):
            raise ValueError('pin must be 1 or 2')
        reg = self._REG_INTMAP1 if pin == 1 else self._REG_INTMAP2
        cur = self._read_reg(reg)
        if active_low:
            cur |= self._INTMAP_INT_LOW
        else:
            cur &= ~self._INTMAP_INT_LOW
        self._write_reg(reg, cur)

    def self_test(self, enabled):
        """Enable or disable the electrostatic self-test force on all axes."""
        st = self._read_reg(self._REG_SELF_TEST)
        if enabled:
            st |= 0x01
        else:
            st &= ~0x01
        self._write_reg(self._REG_SELF_TEST, st)
        if enabled:
            # Spec: wait 4/ODR after asserting or deasserting SELF_TEST.ST
            if self._odr_hz > 0:
                _delay_ms(4000.0 / self._odr_hz + 1)