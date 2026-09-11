"""ADXL345 — 3-axis MEMS accelerometer (Analog Devices).

Provides 3-axis acceleration readings with no configuration beyond the
connection. The chip supports both I²C and SPI; the same driver file is
used on MicroPython, CircuitPython, and Linux hosts.

Default configuration (written at construction):
    - Data format: full-resolution (3.9 mg/LSB at all ranges), ±2 g
    - Output data rate: 100 Hz
    - FIFO: bypass
    - All interrupts disabled

Args:
    connection: Configured I²C or SPI connection pointing at the device.
    bus_type: Bus type string, ``'i2c'`` (default) or ``'spi'``.
        SPI writes prepend a command byte (R/W|MB|A5..A0) to each
        transfer; multi-byte reads set MB=1 so the register pointer
        auto-increments.
"""


_BUS_I2C = 'i2c'
_BUS_SPI = 'spi'

# Default BW_RATE: 100 Hz output data rate, normal power.
_BW_RATE_DEFAULT = 0x0A

# Default DATA_FORMAT: FULL_RES=1, everything else 0 → ±2 g range.
_DATA_FORMAT_DEFAULT = 0x08
# Default POWER_CTL: Measure bit set; everything else cleared.
_POWER_CTL_DEFAULT = 0x08

# Conversion: full-resolution mode is constant 3.9 mg/LSB = 0.0039 g/LSB.
_FULL_RES_SCALE_G_PER_LSB = 3.9e-3

# 10-bit mode scales (g/LSB), indexed by range bits (00=±2, 01=±4, 10=±8, 11=±16).
_10BIT_SCALE_G_PER_LSB = (3.9e-3, 7.8e-3, 15.6e-3, 31.2e-3)

# Valid BW_RATE codes (Rate bits 3:0) and their actual output data rates.
_RATE_CODES = (
    (0x0F, 3200.0),
    (0x0E, 1600.0),
    (0x0D, 800.0),
    (0x0C, 400.0),
    (0x0B, 200.0),
    (0x0A, 100.0),
    (0x09, 50.0),
    (0x08, 25.0),
    (0x07, 12.5),
    (0x06, 6.25),
)


def _delay_ms(ms):
    import time
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


class ADXL345Minimal:
    """ADXL345 3-axis accelerometer — minimal interface.

    Reads X, Y, Z acceleration in *g* with sensible defaults; no
    configuration is required beyond the connection.

    Default configuration (baked in at construction):
        - Full-resolution mode (3.9 mg/LSB at any range)
        - ±2 g measurement range
        - 100 Hz output data rate, normal power
        - FIFO bypass, all interrupts disabled, no offsets

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
        bus_type: Bus type string, ``'i2c'`` (default) or ``'spi'``.
    """

    # Register map (6-bit addresses, 0x00–0x39).
    _REG_DEVID          = 0x00
    _REG_THRESH_TAP     = 0x1D
    _REG_OFSX           = 0x1E
    _REG_OFSY           = 0x1F
    _REG_OFSZ           = 0x20
    _REG_DUR            = 0x21
    _REG_LATENT         = 0x22
    _REG_WINDOW         = 0x23
    _REG_THRESH_ACT     = 0x24
    _REG_THRESH_INACT   = 0x25
    _REG_TIME_INACT     = 0x26
    _REG_ACT_INACT_CTL  = 0x27
    _REG_THRESH_FF      = 0x28
    _REG_TIME_FF        = 0x29
    _REG_TAP_AXES       = 0x2A
    _REG_ACT_TAP_STATUS = 0x2B
    _REG_BW_RATE        = 0x2C
    _REG_POWER_CTL      = 0x2D
    _REG_INT_ENABLE     = 0x2E
    _REG_INT_MAP        = 0x2F
    _REG_INT_SOURCE     = 0x30
    _REG_DATA_FORMAT    = 0x31
    _REG_DATAX0         = 0x32
    _REG_FIFO_CTL       = 0x38
    _REG_FIFO_STATUS    = 0x39

    # Fixed device ID; reading any other value indicates wrong device/wiring.
    _DEVID_VALUE = 0xE5

    def __init__(self, connection, bus_type=_BUS_I2C):
        self._connection = connection
        self._bus_type = bus_type
        self._range_bits = 0   # 0..3: 0=±2, 1=±4, 2=±8, 3=±16 g
        self._full_res = True
        self._write_reg(self._REG_DATA_FORMAT, _DATA_FORMAT_DEFAULT)
        self._write_reg(self._REG_BW_RATE, _BW_RATE_DEFAULT)
        self._write_reg(self._REG_POWER_CTL, _POWER_CTL_DEFAULT)
        devid = self._read_reg(self._REG_DEVID)
        if devid != self._DEVID_VALUE:
            raise ValueError('ADXL345 DEVID: expected 0x{:02X}, got 0x{:02X}'.format(
                self._DEVID_VALUE, devid))
        # Per datasheet: allow at least one ODR period before reading data.
        _delay_ms(11)

    def _cmd_byte(self, reg, read=False, multi=False):
        """Build the SPI command byte for the ADXL345.

        I²C calls bypass this — the connection's address byte is independent.
        """
        addr = (reg & 0x3F)
        if multi:
            addr |= 0x40
        if read:
            addr |= 0x80
        return addr

    def _write_reg(self, reg, value):
        if self._bus_type == _BUS_SPI:
            cmd = self._cmd_byte(reg, read=False, multi=False)
            self._connection.write(bytes([cmd, value & 0xFF]))
        else:
            self._connection.write(bytes([reg & 0xFF, value & 0xFF]))

    def _read_reg(self, reg):
        if self._bus_type == _BUS_SPI:
            cmd = self._cmd_byte(reg, read=True, multi=False)
            return self._connection.write_read(bytes([cmd]), 1)[0]
        return self._connection.write_read(bytes([reg & 0xFF]), 1)[0]

    def _read_burst(self, reg, n):
        """Read n bytes starting at reg, auto-incrementing the register pointer."""
        if self._bus_type == _BUS_SPI:
            cmd = self._cmd_byte(reg, read=True, multi=(n > 1))
            return self._connection.write_read(bytes([cmd]), n)
        return self._connection.write_read(bytes([reg & 0xFF]), n)

    def read(self):
        """Read 3-axis linear acceleration.

        Reads all six data bytes (DATAX0..DATAZ1) in one burst so the X, Y,
        Z samples are guaranteed to come from a single measurement.

        Returns:
            tuple: (x, y, z) acceleration in *g*.
        """
        raw = self._read_burst(self._REG_DATAX0, 6)
        # Each axis is little-endian, signed 16-bit two's complement.
        rx = raw[0] | (raw[1] << 8)
        ry = raw[2] | (raw[3] << 8)
        rz = raw[4] | (raw[5] << 8)
        if rx & 0x8000:
            rx -= 0x10000
        if ry & 0x8000:
            ry -= 0x10000
        if rz & 0x8000:
            rz -= 0x10000
        if self._full_res:
            scale = _FULL_RES_SCALE_G_PER_LSB
        else:
            scale = _10BIT_SCALE_G_PER_LSB[self._range_bits & 0x03]
        return (rx * scale, ry * scale, rz * scale)


class ADXL345Full(ADXL345Minimal):
    """ADXL345 full interface — extends ADXL345Minimal with configuration, FIFO,
    tap / activity / inactivity / free-fall detection, and interrupt routing.

    Adds:
        - Range and data-rate selection (FULL_RES preserved).
        - Low-power mode and self-test.
        - Per-axis offset calibration (in *g*).
        - Single/double-tap detection with configurable threshold, duration,
          latency, and window.
        - Activity and inactivity detection with ac/dc coupling.
        - Free-fall detection.
        - 32-level FIFO (bypass / FIFO / stream / trigger modes).
        - Interrupt routing (INT1 / INT2) and source read.
        - Sleep, auto-sleep, and link mode for power management.

    Args:
        connection: Configured I²C or SPI connection pointing at the device.
        bus_type: Bus type string, ``'i2c'`` (default) or ``'spi'``.
    """

    # Interrupt source bits — match INT_ENABLE / INT_MAP / INT_SOURCE layout.
    INT_DATA_READY  = 0x80
    INT_SINGLE_TAP  = 0x40
    INT_DOUBLE_TAP  = 0x20
    INT_ACTIVITY    = 0x10
    INT_INACTIVITY  = 0x08
    INT_FREE_FALL   = 0x04
    INT_WATERMARK   = 0x02
    INT_OVERRUN     = 0x01

    # FIFO mode values — match FIFO_CTL bits 7:6.
    FIFO_BYPASS  = 0x00
    FIFO_FIFO    = 0x40
    FIFO_STREAM  = 0x80
    FIFO_TRIGGER = 0xC0

    # Sleep-mode wakeup sample rates (POWER_CTL Wakeup bits 2:1).
    WAKEUP_8_HZ = 0x00
    WAKEUP_4_HZ = 0x02
    WAKEUP_2_HZ = 0x04
    WAKEUP_1_HZ = 0x06

    def __init__(self, connection, bus_type=_BUS_I2C):
        super().__init__(connection, bus_type)

    def set_range(self, range_g):
        """Set the measurement range.

        Args:
            range_g: One of 2, 4, 8, 16 (g). FULL_RES is preserved, so the
                scale factor stays 3.9 mg/LSB regardless of range.
        """
        code = {2: 0, 4: 1, 8: 2, 16: 3}.get(range_g)
        if code is None:
            raise ValueError('range_g must be one of 2, 4, 8, 16')
        self._range_bits = code
        df = self._read_reg(self._REG_DATA_FORMAT)
        df = (df & ~0x03) | (code & 0x03)
        if self._full_res:
            df |= 0x08
        self._write_reg(self._REG_DATA_FORMAT, df)

    def set_data_rate(self, rate_hz):
        """Set the output data rate to the nearest supported value.

        Args:
            rate_hz: Requested ODR in Hz; clamped to the nearest of
                6.25, 12.5, 25, 50, 100, 200, 400, 800, 1600, 3200 Hz.
        """
        best_code = _RATE_CODES[-1][0]
        best_rate = _RATE_CODES[-1][1]
        for code, actual in _RATE_CODES:
            if abs(actual - rate_hz) < abs(best_rate - rate_hz):
                best_code, best_rate = code, actual
        bw = self._read_reg(self._REG_BW_RATE)
        bw = (bw & ~0x0F) | (best_code & 0x0F)
        self._write_reg(self._REG_BW_RATE, bw)

    def set_low_power(self, enabled):
        """Enable or disable low-power mode (higher noise)."""
        bw = self._read_reg(self._REG_BW_RATE)
        if enabled:
            bw |= 0x10
        else:
            bw &= ~0x10
        self._write_reg(self._REG_BW_RATE, bw)

    def set_offset(self, x, y, z):
        """Set per-axis offset in *g*.

        Offsets are added to the output after filtering; the scale factor is
        15.6 mg/LSB and the signed value is clamped to ±2 *g* (≈ ±128 LSB).

        Args:
            x: X offset in *g*.
            y: Y offset in *g*.
            z: Z offset in *g*.
        """
        self._write_reg(self._REG_OFSX, self._encode_offset(x))
        self._write_reg(self._REG_OFSY, self._encode_offset(y))
        self._write_reg(self._REG_OFSZ, self._encode_offset(z))

    @staticmethod
    def _encode_offset(offset_g):
        raw = int(round(offset_g / 15.6e-3))
        if raw > 127:
            raw = 127
        if raw < -128:
            raw = -128
        return raw & 0xFF

    def calibrate_offset(self, target_x=0.0, target_y=0.0, target_z=1.0, samples=128):
        """Measure and write per-axis offsets to bring readings onto the targets.

        Averages ``samples`` reads and computes the residual (target − mean),
        then writes the negative residual into the offset registers. Call
        with the sensor stationary and oriented so the Z axis points up
        (target_z=1 *g*) for a typical gravity-referenced calibration.

        Args:
            target_x: Expected X reading during calibration (in *g*).
            target_y: Expected Y reading during calibration (in *g*).
            target_z: Expected Z reading during calibration (in *g*).
            samples: Number of samples to average.
        """
        sx = sy = sz = 0.0
        for _ in range(samples):
            x, y, z = self.read()
            sx += x
            sy += y
            sz += z
            _delay_ms(11)
        sx /= samples
        sy /= samples
        sz /= samples
        self.set_offset(target_x - sx, target_y - sy, target_z - sz)

    def set_tap_detection(self, threshold_g, duration_ms, axes=0x07, suppress=False):
        """Configure single-tap detection and enable the SINGLE_TAP interrupt.

        Args:
            threshold_g: Tap acceleration threshold in *g* (62.5 mg/LSB).
            duration_ms: Maximum tap duration in ms (625 µs/LSB).
            axes: Bitmask of axes that participate (bit 2=X, 1=Y, 0=Z; default 0x07).
            suppress: Suppress double-tap if acceleration persists between taps.
        """
        self._write_reg(self._REG_THRESH_TAP, int(round(threshold_g / 62.5e-3)) & 0xFF)
        self._write_reg(self._REG_DUR, int(round(duration_ms / 0.625)) & 0xFF)
        tap_axes = (axes & 0x07) | (0x08 if suppress else 0x00)
        self._write_reg(self._REG_TAP_AXES, tap_axes)
        self._enable_interrupt(self.INT_SINGLE_TAP)

    def set_double_tap(self, latency_ms, window_ms):
        """Configure double-tap latency and window; enables DOUBLE_TAP interrupt.

        Args:
            latency_ms: Time after a tap when a second tap is expected (1.25 ms/LSB).
            window_ms: Time window after latency during which the second tap must occur (1.25 ms/LSB).
        """
        self._write_reg(self._REG_LATENT, int(round(latency_ms / 1.25)) & 0xFF)
        self._write_reg(self._REG_WINDOW, int(round(window_ms / 1.25)) & 0xFF)
        self._enable_interrupt(self.INT_DOUBLE_TAP)

    def set_activity(self, threshold_g, axes=0x70, ac_coupled=True):
        """Configure activity detection.

        Args:
            threshold_g: Activity threshold in *g* (62.5 mg/LSB).
            axes: ACT_INACT_CTL activity bits (bits 7:4: ACT ac/dc, ACT_X
                enable, ACT_Y enable, ACT_Z enable). Default 0x70 =
                ac-coupled, all axes enabled.
            ac_coupled: If True (default), use ac-coupled activity detection
                (filters out the static gravity vector).
        """
        self._write_reg(self._REG_THRESH_ACT, int(round(threshold_g / 62.5e-3)) & 0xFF)
        aic = self._read_reg(self._REG_ACT_INACT_CTL)
        aic &= ~0xF0  # clear ACT ac/dc and ACT axis enables
        if ac_coupled:
            aic |= 0x80
        aic |= axes & 0x70
        self._write_reg(self._REG_ACT_INACT_CTL, aic)
        self._enable_interrupt(self.INT_ACTIVITY)

    def set_inactivity(self, threshold_g, time_sec, axes=0x07, ac_coupled=False):
        """Configure inactivity detection.

        Args:
            threshold_g: Inactivity threshold in *g* (62.5 mg/LSB).
            time_sec: Inactivity time in seconds (1 s/LSB).
            axes: ACT_INACT_CTL inactivity bits (bits 3:0: INACT ac/dc,
                INACT_X enable, INACT_Y enable, INACT_Z enable). Default 0x07 =
                dc-coupled, all axes enabled.
            ac_coupled: If True, use ac-coupled inactivity detection.
        """
        self._write_reg(self._REG_THRESH_INACT, int(round(threshold_g / 62.5e-3)) & 0xFF)
        self._write_reg(self._REG_TIME_INACT, int(round(time_sec)) & 0xFF)
        aic = self._read_reg(self._REG_ACT_INACT_CTL)
        aic &= ~0x0F  # clear INACT ac/dc and INACT axis enables
        if ac_coupled:
            aic |= 0x08
        aic |= axes & 0x07
        self._write_reg(self._REG_ACT_INACT_CTL, aic)
        self._enable_interrupt(self.INT_INACTIVITY)

    def set_free_fall(self, threshold_g, time_ms):
        """Configure free-fall detection and enable the FREE_FALL interrupt.

        Args:
            threshold_g: Free-fall threshold in *g* (62.5 mg/LSB).
                Recommended range 0.3–0.6 *g* (codes 0x05–0x09).
            time_ms: Free-fall time in ms (5 ms/LSB). Recommended 100–350 ms
                (codes 0x14–0x46).
        """
        self._write_reg(self._REG_THRESH_FF, int(round(threshold_g / 62.5e-3)) & 0xFF)
        self._write_reg(self._REG_TIME_FF, int(round(time_ms / 5.0)) & 0xFF)
        self._enable_interrupt(self.INT_FREE_FALL)

    def set_interrupt(self, source, enabled, pin=1):
        """Enable or disable an interrupt source and route it to INT1 or INT2.

        Args:
            source: One of the ``INT_*`` constants.
            enabled: True to enable, False to disable.
            pin: 1 (default) for INT1, 2 for INT2.
        """
        ie = self._read_reg(self._REG_INT_ENABLE)
        im = self._read_reg(self._REG_INT_MAP)
        if enabled:
            ie |= source
            if pin == 2:
                im |= source
            else:
                im &= ~source
        else:
            ie &= ~source
        self._write_reg(self._REG_INT_ENABLE, ie)
        self._write_reg(self._REG_INT_MAP, im)

    def _enable_interrupt(self, source):
        self.set_interrupt(source, True, pin=1)

    def read_interrupt_source(self):
        """Read the INT_SOURCE register; clears latched interrupts.

        Returns:
            int: Bitmask of currently active interrupt sources (see ``INT_*``).
        """
        return self._read_reg(self._REG_INT_SOURCE)

    def set_fifo_mode(self, mode, samples=16):
        """Configure the FIFO.

        Args:
            mode: One of ``FIFO_BYPASS`` (0x00), ``FIFO_FIFO`` (0x40),
                ``FIFO_STREAM`` (0x80), ``FIFO_TRIGGER`` (0xC0).
            samples: Watermark level for FIFO / Stream mode, or number of
                samples to retain before triggering for Trigger mode.
        """
        fifo_ctl = (mode & 0xC0) | (samples & 0x1F)
        self._write_reg(self._REG_FIFO_CTL, fifo_ctl)

    def fifo_count(self):
        """Return the number of FIFO entries currently available (0–32)."""
        status = self._read_reg(self._REG_FIFO_STATUS)
        return status & 0x3F

    def read_fifo(self):
        """Drain the FIFO, returning all available samples.

        Each sample is the same 6-byte X/Y/Z burst as ``read()`` returns.

        Returns:
            list[tuple]: List of (x, y, z) tuples in *g*. Empty if FIFO is empty.
        """
        n = self.fifo_count()
        if n == 0:
            return []
        out = []
        for _ in range(n):
            raw = self._read_burst(self._REG_DATAX0, 6)
            rx = raw[0] | (raw[1] << 8)
            ry = raw[2] | (raw[3] << 8)
            rz = raw[4] | (raw[5] << 8)
            if rx & 0x8000:
                rx -= 0x10000
            if ry & 0x8000:
                ry -= 0x10000
            if rz & 0x8000:
                rz -= 0x10000
            if self._full_res:
                scale = _FULL_RES_SCALE_G_PER_LSB
            else:
                scale = _10BIT_SCALE_G_PER_LSB[self._range_bits & 0x03]
            out.append((rx * scale, ry * scale, rz * scale))
        return out

    def set_sleep(self, enabled, wakeup_hz=8):
        """Enter or leave sleep mode.

        Args:
            enabled: True to sleep, False to wake.
            wakeup_hz: Sample rate during sleep (8 / 4 / 2 / 1 Hz).
        """
        pwr = self._read_reg(self._REG_POWER_CTL)
        if enabled:
            wakeup_code = {8: self.WAKEUP_8_HZ, 4: self.WAKEUP_4_HZ,
                           2: self.WAKEUP_2_HZ, 1: self.WAKEUP_1_HZ}.get(wakeup_hz)
            if wakeup_code is None:
                raise ValueError('wakeup_hz must be 8, 4, 2, or 1')
            pwr = (pwr & ~0x06) | wakeup_code | 0x08  # keep Measure bit set
            pwr |= 0x04  # Sleep=1
        else:
            pwr &= ~0x04
        self._write_reg(self._REG_POWER_CTL, pwr)

    def set_link_mode(self, enabled):
        """Enable or disable the activity/inactivity serial-link mode."""
        pwr = self._read_reg(self._REG_POWER_CTL)
        if enabled:
            pwr |= 0x40
        else:
            pwr &= ~0x40
        self._write_reg(self._REG_POWER_CTL, pwr)

    def set_auto_sleep(self, enabled):
        """Enable or disable auto-sleep on inactivity (requires Link=1)."""
        pwr = self._read_reg(self._REG_POWER_CTL)
        if enabled:
            pwr |= 0x20
        else:
            pwr &= ~0x20
        self._write_reg(self._REG_POWER_CTL, pwr)

    def self_test(self, enabled):
        """Enable or disable the electrostatic self-test force on all axes."""
        df = self._read_reg(self._REG_DATA_FORMAT)
        if enabled:
            df |= 0x80
        else:
            df &= ~0x80
        self._write_reg(self._REG_DATA_FORMAT, df)