"""BMA180 — 3-axis MEMS accelerometer (Bosch Sensortec).

Triaxial low-g accelerometer with 14-bit digital output and seven selectable
full-scale ranges (±1 to ±16 *g*). Provides eight low-pass filters (10–1200 Hz)
plus a high-pass and band-pass option, four noise/power sub-modes, an 8-bit
temperature output (0.5 K/LSB), and an on-chip interrupt engine (new-data,
low-g, high-g, slope, alert, double-tap) with per-axis enables and latched or
self-resetting behaviour.

Communicates over I²C at address 0x40 (SDO = GND) or 0x41 (SDO = VDDIO).
The chip also supports 4-wire SPI; out of scope here.

The driver is platform-agnostic — it only calls the connection interface.
The same file is used on MicroPython, CircuitPython, and Linux hosts.
"""

from periph.connection.register import to_signed

# Default range: ±2 g (OFFSET_LSB1 0x35 bits 3:1 = 010).
_DEFAULT_RANGE_G = 2
# Default bandwidth: 150 Hz low-pass (BW_TCS 0x20 bits 7:4 = 0100).
_DEFAULT_BANDWIDTH_HZ = 150

# Range bits (OFFSET_LSB1 0x35) bits 3:1 — 111 not authorised.
_RANGE_BITS = {1: 0x00, 1.5: 0x02, 2: 0x04, 3: 0x06, 4: 0x08, 8: 0x0A, 16: 0x0C}

# Sensitivity (LSB/g) by range.
_RANGE_SCALE = {1: 8192, 1.5: 5460, 2: 4096, 3: 2730, 4: 2048, 8: 1024, 16: 512}

# Low-pass bandwidth codes (BW_TCS bits 7:4), 1010-1111 not authorised.
_BW_CODES = (
    (10, 0x00),
    (20, 0x10),
    (40, 0x20),
    (75, 0x30),
    (150, 0x40),
    (300, 0x50),
    (600, 0x60),
    (1200, 0x70),
)
# Filter mode codes (BW_TCS bits 7:4): 1000 high-pass 1 Hz, 1001 band-pass 0.2-300 Hz.
_FILTER_CODES = {
    'low_pass':  None,           # set by _nearest_bandwidth
    'high_pass': 0x80,
    'band_pass': 0x90,
}

# Mode config bits (TCO_Z 0x30 bits 1:0).
_MODE_CODES = {
    'low_noise':         0x00,  # 1025 µA, 1200 Hz max
    'ultra_low_noise':   0x01,
    'low_noise_reduced': 0x02,  # 900 µA, 150 Hz max
    'low_power':         0x03,  # 650 µA, bandwidth halved
}

# Wake-up duration (TCO_Y 0x2F bits 1:0).
_WAKEUP_DUR_CODES = {20: 0x00, 80: 0x01, 320: 0x02, 2560: 0x03}

# Slope duration (TCO_X 0x2E bits 1:0).
_SLOPE_DUR_CODES = {1: 0x00, 3: 0x01, 5: 0x02, 7: 0x03}

# Tap sensitivity duration (GAIN_T 0x31 bits 2:0).
_TAP_DUR_CODES = {50: 0x00, 75: 0x01, 100: 0x02, 150: 0x03,
                 250: 0x04, 500: 0x05, 750: 0x06, 1000: 0x07}

# Debounce counters (LG bits 3:2 / HG bits 5:4 of CTRL_REG4 0x22).
_DEBOUNCE_CODES = {0: 0x00, 1: 0x04, 2: 0x08, 3: 0x0C}

# Duration time base: T_update = 417 µs, *dur = 5 * T_update ≈ 2.085 ms/LSB.
_DUR_LSB_MS = 2.085


def _delay_ms(ms):
    import time
    if hasattr(time, 'sleep_ms'):
        time.sleep_ms(ms)
    else:
        time.sleep(ms / 1000.0)


def _nearest_bandwidth(bw_hz):
    best_code = _BW_CODES[0][1]
    best_hz = _BW_CODES[0][0]
    for hz, code in _BW_CODES:
        if abs(hz - bw_hz) < abs(best_hz - bw_hz):
            best_code = code
            best_hz = hz
    return best_code, best_hz


def _nearest_tap_dur(window_ms):
    best_code = _TAP_DUR_CODES[50]
    best_ms = 50
    for ms, code in _TAP_DUR_CODES.items():
        if ms >= window_ms and abs(ms - window_ms) < abs(best_ms - window_ms):
            best_code = code
            best_ms = ms
    # If window_ms is below 50ms, snap to 50ms.
    if best_ms > window_ms and best_ms != 50:
        best_code = _TAP_DUR_CODES[50]
        best_ms = 50
    return best_code, best_ms


class BMA180Minimal:
    """BMA180 3-axis accelerometer — minimal interface.

    Reads X, Y, Z acceleration in *g* with sensible defaults; no
    configuration is required beyond the connection.

    Default configuration (baked in at construction):
        - Range ±2 *g* (4096 LSB/g)
        - Bandwidth 150 Hz low-pass
        - mode_config = 00 (low-noise, factory-calibrated)
        - 14-bit readout, shadow_dis = 0
        - All interrupt enables left untouched
        - Calibration bits preserved everywhere

    Args:
        connection: Configured ``RegisterConnection`` (I²C, SMBus, or SPI;
            I²C address is 0x40 / 0x41).
    """

    # Register map (0x00..0x3A).
    _REG_CHIP_ID          = 0x00
    _REG_VERSION          = 0x01
    _REG_ACC_X_LSB        = 0x02
    _REG_ACC_X_MSB        = 0x03
    _REG_ACC_Y_LSB        = 0x04
    _REG_ACC_Y_MSB        = 0x05
    _REG_ACC_Z_LSB        = 0x06
    _REG_ACC_Z_MSB        = 0x07
    _REG_TEMP             = 0x08
    _REG_STATUS_REG1      = 0x09
    _REG_STATUS_REG2      = 0x0A
    _REG_STATUS_REG3      = 0x0B
    _REG_STATUS_REG4      = 0x0C
    _REG_CTRL_REG0        = 0x0D
    _REG_CTRL_REG1        = 0x0E
    _REG_CTRL_REG2        = 0x0F
    _REG_RESET            = 0x10
    _REG_BW_TCS           = 0x20
    _REG_CTRL_REG3        = 0x21
    _REG_CTRL_REG4        = 0x22
    _REG_HY               = 0x23
    _REG_SLOPE_TAPSENS    = 0x24
    _REG_HIGH_LOW_INFO    = 0x25
    _REG_LOW_DUR          = 0x26
    _REG_HIGH_DUR         = 0x27
    _REG_TAPSENS_TH       = 0x28
    _REG_LOW_TH           = 0x29
    _REG_HIGH_TH          = 0x2A
    _REG_SLOPE_TH         = 0x2B
    _REG_CD1              = 0x2C
    _REG_CD2              = 0x2D
    _REG_TCO_X            = 0x2E
    _REG_TCO_Y            = 0x2F
    _REG_TCO_Z            = 0x30
    _REG_GAIN_T           = 0x31
    _REG_GAIN_X           = 0x32
    _REG_GAIN_Y           = 0x33
    _REG_GAIN_Z           = 0x34
    _REG_OFFSET_LSB1      = 0x35
    _REG_OFFSET_LSB2      = 0x36
    _REG_OFFSET_T         = 0x37
    _REG_OFFSET_X         = 0x38
    _REG_OFFSET_Y         = 0x39
    _REG_OFFSET_Z         = 0x3A

    # CHIP_ID register 0x00 bits 2:0 = 0b011 (0x03).
    _CHIP_ID_VALUE = 0x03
    _CHIP_ID_MASK = 0x07

    # CTRL_REG0 bits.
    _CTRL_REG0_EE_W        = 0x10
    _CTRL_REG0_RESET_INT   = 0x40
    _CTRL_REG0_UPDATE_IMG  = 0x20
    _CTRL_REG0_SLEEP       = 0x02

    # Soft-reset code (write to RESET 0x10).
    _SOFT_RESET_CMD = 0xB6

    def __init__(self, connection):
        self._connection = connection
        self._range_g = _DEFAULT_RANGE_G
        self._range_bits = _RANGE_BITS[_DEFAULT_RANGE_G]
        # First transaction must be something other than an acc LSB read
        # (the chip returns MSB=0 for a first LSB read after power-up).
        # Read CHIP_ID first.
        chip_id = self._read_reg(self._REG_CHIP_ID)
        if (chip_id & self._CHIP_ID_MASK) != self._CHIP_ID_VALUE:
            raise ValueError('BMA180 CHIP_ID: expected 0x{:02X}, got 0x{:02X}'.format(
                self._CHIP_ID_VALUE, chip_id & self._CHIP_ID_MASK))
        # Unlock image registers (0x20-0x3B) by setting ee_w = 1.
        ctrl0 = self._read_reg(self._REG_CTRL_REG0)
        ctrl0 |= self._CTRL_REG0_EE_W
        self._write_reg(self._REG_CTRL_REG0, ctrl0)
        # Set range = ±2 g via OFFSET_LSB1 bits 3:1, preserving offset_x LSBs
        # (bits 7:4) and smp_skip (bit 0).
        olsb1 = self._read_reg(self._REG_OFFSET_LSB1)
        olsb1 = (olsb1 & 0xF1) | self._range_bits
        self._write_reg(self._REG_OFFSET_LSB1, olsb1)
        # Set bw = 150 Hz via BW_TCS bits 7:4, preserving tcs bits 3:0.
        bw_code, _ = _nearest_bandwidth(_DEFAULT_BANDWIDTH_HZ)
        bw = self._read_reg(self._REG_BW_TCS)
        bw = (bw & 0x0F) | bw_code
        self._write_reg(self._REG_BW_TCS, bw)
        # Wait 1/(2*bw) for filtered data to settle.
        _delay_ms(4)

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, value & 0xFF)

    def _read_reg(self, reg):
        return self._connection.read_reg(reg, 1)[0]

    def _read_burst(self, reg, n):
        return self._connection.read_reg(reg, n)

    def read(self):
        """Read 3-axis linear acceleration.

        Burst-reads the six LSB-then-MSB data bytes (0x02..0x07) so the X, Y,
        Z samples are guaranteed to come from a single measurement with
        ``shadow_dis=0`` enforcing correct ordering.

        Returns:
            tuple: (x, y, z) acceleration in *g*.
        """
        raw = self._read_burst(self._REG_ACC_X_LSB, 6)
        # 14-bit two's complement; bit 1 of each LSB is 0, bit 0 is new_data.
        rx = (((raw[1] << 6) | (raw[0] >> 2)) & 0x3FFF)
        ry = (((raw[3] << 6) | (raw[2] >> 2)) & 0x3FFF)
        rz = (((raw[5] << 6) | (raw[4] >> 2)) & 0x3FFF)
        sx = to_signed(rx, 14)
        sy = to_signed(ry, 14)
        sz = to_signed(rz, 14)
        scale = _RANGE_SCALE[self._range_g]
        return (sx / scale, sy / scale, sz / scale)


# BMA180Full uses BMA180Minimal by inheritance.

class BMA180Full(BMA180Minimal):
    """BMA180 full interface — extends BMA180Minimal with configuration,
    range/bandwidth selection, mode control, temperature, new-data, shadow,
    sample-skip, low-g/high-g/slope/alert/tap interrupts with per-axis
    enable and filter selection, latched or self-resetting interrupts,
    self-wake-up, sleep, soft reset, electrostatic self-test, offset
    regulation, version register, and the two CUSTOMER scratch bytes.
    """

    # Interrupt source constants — match the bit layout used by enable_interrupt.
    SOURCE_LOW_G    = 0x01
    SOURCE_HIGH_G   = 0x02
    SOURCE_SLOPE    = 0x04
    SOURCE_ALERT    = 0x08
    SOURCE_TAP      = 0x10
    SOURCE_NEW_DATA = 0x20

    # STATUS_REG3 latched interrupt flag bits.
    _STATUS_HIGH_G    = 0x80
    _STATUS_LOW_G     = 0x40
    _STATUS_SLOPE     = 0x20
    _STATUS_TAP       = 0x10
    _STATUS_X_FIRST   = 0x04
    _STATUS_Y_FIRST   = 0x02
    _STATUS_Z_FIRST   = 0x01

    # CTRL_REG3 bits (interrupt enables).
    _CTRL_REG3_SLOPE_ALERT  = 0x80
    _CTRL_REG3_SLOPE_INT    = 0x40
    _CTRL_REG3_HIGH_INT     = 0x20
    _CTRL_REG3_LOW_INT      = 0x10
    _CTRL_REG3_TAP_INT      = 0x08
    _CTRL_REG3_ADV_INT      = 0x04
    _CTRL_REG3_NEW_DATA_INT = 0x02
    _CTRL_REG3_LAT_INT      = 0x01

    # HIGH_LOW_INFO bits: bits 7:5 high_int_x/y/z, bit 4 high_filt, bits 3:1 low_int_x/y/z, bit 0 low_filt.
    # SLOPE_TAPSENS_INFO bits: bits 7:5 slope_int_x/y/z, bit 4 slope_filt, bits 3:1 tapsens_int_x/y/z, bit 0 tapsens_filt.
    _HIGH_AXIS_SHIFT = 5
    _LOW_AXIS_SHIFT  = 1
    _SLOPE_AXIS_SHIFT = 5
    _TAP_AXIS_SHIFT  = 1
    _HIGH_FILT_BIT   = 0x10
    _LOW_FILT_BIT    = 0x01
    _SLOPE_FILT_BIT  = 0x10
    _TAP_FILT_BIT    = 0x01

    # HY register bits: high_hy<4:0> bits 7:3, low_hy<4:2> bits 2:0.
    _HY_HIGH_SHIFT = 3
    _HY_LOW_MASK   = 0x07

    # CTRL_REG4 bits: low_hy<1:0> bits 7:6, mot_cd_r bits 5:4, ff_cd_r bits 3:2, offset_finetuning bits 1:0.
    _CR4_LOW_HY_SHIFT = 6
    _CR4_MOT_CD_SHIFT = 4
    _CR4_FF_CD_SHIFT  = 2

    # OFFSET_LSB1 bits: offset_x<3:0> bits 7:4, range<2:0> bits 3:1, smp_skip bit 0.
    _OLSB1_RANGE_MASK = 0x0E
    _OLSB1_SMP_SKIP   = 0x01

    # GAIN_X bits: gain_x<6:0> bits 7:1, dis_reg bit 0.
    _GAIN_X_DIS_REG   = 0x01
    # GAIN_Y bits: gain_y<6:0> bits 7:1, shadow_dis bit 0.
    _GAIN_Y_SHADOW    = 0x01
    # GAIN_Z bits: gain_z<6:0> bits 7:1, wake_up bit 0.
    _GAIN_Z_WAKE_UP   = 0x01

    # BW_TCS bits: bw<3:0> bits 7:4, tcs<3:0> bits 3:0.
    _BW_MASK = 0xF0
    # TCO_X bits: tco_x<5:0> bits 7:2, slope_dur<1:0> bits 1:0.
    _TCO_X_SLOPE_MASK = 0x03
    # TCO_Y bits: tco_y<5:0> bits 7:2, wake_up_dur<1:0> bits 1:0.
    _TCO_Y_WAKE_MASK  = 0x03
    # TCO_Z bits: tco_z<5:0> bits 7:2, mode_config<1:0> bits 1:0.
    _TCO_Z_MODE_MASK  = 0x03
    # GAIN_T bits: gain_t<4:0> bits 7:3, tapsens_dur<2:0> bits 2:0.
    _GAIN_T_TAP_MASK  = 0x07

    # LOW_DUR bits: low_dur<6:0> bits 7:1, tco_range bit 0 (preserve!).
    _LOW_DUR_MASK = 0xFE
    # HIGH_DUR bits: high_dur<6:0> bits 7:1, dis_i2c bit 0.
    _HIGH_DUR_MASK = 0xFE

    # OFFSET_T bits: offset_t<6:0> bits 7:1, readout_12bit bit 0.
    _OFFSET_T_12BIT = 0x01

    def __init__(self, connection):
        super().__init__(connection)
        self._enabled_sources = 0
        self._sleeping = False

    def read(self):
        """Read 3-axis linear acceleration in *g*. Delegates to :meth:`BMA180Minimal.read`."""
        return super().read()

    def set_range(self, range_g):
        """Set the measurement range to ±1/±1.5/±2/±3/±4/±8/±16 *g*."""
        if range_g not in _RANGE_BITS:
            raise ValueError('range_g must be one of 1, 1.5, 2, 3, 4, 8, 16')
        self._range_g = range_g
        self._range_bits = _RANGE_BITS[range_g]
        olsb1 = self._read_reg(self._REG_OFFSET_LSB1)
        olsb1 = (olsb1 & ~self._OLSB1_RANGE_MASK) | self._range_bits
        self._write_reg(self._REG_OFFSET_LSB1, olsb1)

    def set_bandwidth(self, bandwidth_hz):
        """Set the low-pass bandwidth to the nearest supported value (10..1200 Hz)."""
        bw_code, _ = _nearest_bandwidth(bandwidth_hz)
        bw = self._read_reg(self._REG_BW_TCS)
        bw = (bw & 0x0F) | bw_code
        self._write_reg(self._REG_BW_TCS, bw)
        _delay_ms(4)

    def set_filter_mode(self, mode):
        """Set the filter mode: 0 = low-pass (use set_bandwidth), 1 = high-pass 1 Hz, 2 = band-pass 0.2–300 Hz."""
        if mode == 0:
            return  # caller uses set_bandwidth() to set the low-pass frequency
        if mode == 1:
            code = _FILTER_CODES['high_pass']
        elif mode == 2:
            code = _FILTER_CODES['band_pass']
        else:
            raise ValueError('mode must be 0 (low-pass), 1 (high-pass), or 2 (band-pass)')
        bw = self._read_reg(self._REG_BW_TCS)
        bw = (bw & 0x0F) | code
        self._write_reg(self._REG_BW_TCS, bw)
        _delay_ms(4)

    def set_mode(self, mode):
        """Set the noise/power sub-mode (0..3).

        The chip is factory-calibrated for mode 0; other modes shift the
        offset and need ``calibrate_offset()`` to recover accuracy.
        """
        if mode not in _MODE_CODES.values() and mode not in range(4):
            raise ValueError('mode must be 0, 1, 2, or 3')
        tcoz = self._read_reg(self._REG_TCO_Z)
        tcoz = (tcoz & ~self._TCO_Z_MODE_MASK) | (mode & 0x03)
        self._write_reg(self._REG_TCO_Z, tcoz)

    def set_resolution(self, bits):
        """Set the data resolution: 12 or 14 bits (readout_12bit)."""
        if bits not in (12, 14):
            raise ValueError('bits must be 12 or 14')
        ot = self._read_reg(self._REG_OFFSET_T)
        if bits == 12:
            ot |= self._OFFSET_T_12BIT
        else:
            ot &= ~self._OFFSET_T_12BIT & 0xFF
        self._write_reg(self._REG_OFFSET_T, ot)

    def read_raw(self):
        """Read raw 14-bit two's-complement acceleration counts.

        Returns:
            tuple: (x, y, z) signed counts in [-8192, 8191].
        """
        raw = self._read_burst(self._REG_ACC_X_LSB, 6)
        rx = (((raw[1] << 6) | (raw[0] >> 2)) & 0x3FFF)
        ry = (((raw[3] << 6) | (raw[2] >> 2)) & 0x3FFF)
        rz = (((raw[5] << 6) | (raw[4] >> 2)) & 0x3FFF)
        return (to_signed(rx, 14), to_signed(ry, 14), to_signed(rz, 14))

    def read_temperature(self):
        """Read on-chip temperature.

        Returns:
            float: Temperature in °C, computed as ``25.0 + (int8(raw) - 2) * 0.5``.
            Indicative; calibrate ``offset_t`` for accuracy.
        """
        raw = self._read_reg(self._REG_TEMP)
        return 25.0 + (((raw if raw < 128 else raw - 256) - 2) * 0.5)

    def new_data_available(self):
        """Return True if all three ``new_data_*`` bits in the LSB registers are set."""
        x_lsb = self._read_reg(self._REG_ACC_X_LSB)
        y_lsb = self._read_reg(self._REG_ACC_Y_LSB)
        z_lsb = self._read_reg(self._REG_ACC_Z_LSB)
        return bool((x_lsb & 0x01) and (y_lsb & 0x01) and (z_lsb & 0x01))

    def set_shadow(self, enabled):
        """Enable or disable MSB-only reads (shadow_dis = not enabled)."""
        gy = self._read_reg(self._REG_GAIN_Y)
        if enabled:
            gy &= ~self._GAIN_Y_SHADOW & 0xFF
        else:
            gy |= self._GAIN_Y_SHADOW
        self._write_reg(self._REG_GAIN_Y, gy)

    def set_sample_skip(self, enabled):
        """Set ``smp_skip`` (only useful with the new-data interrupt)."""
        olsb1 = self._read_reg(self._REG_OFFSET_LSB1)
        if enabled:
            olsb1 |= self._OLSB1_SMP_SKIP
        else:
            olsb1 &= ~self._OLSB1_SMP_SKIP & 0xFF
        self._write_reg(self._REG_OFFSET_LSB1, olsb1)

    def set_low_g(self, threshold_g, duration_ms, hysteresis_g=0, axes=0x07, counter=0, filtered=True):
        """Configure the low-g (free-fall) interrupt and enable it.

        Args:
            threshold_g: |a| threshold per axis in *g*.
            duration_ms: Minimum time all three |a| must stay below the
                threshold, in milliseconds.
            hysteresis_g: Hysteresis in *g*.
            axes: Bitmask of enabled axes (bit 0 = X, bit 1 = Y, bit 2 = Z).
            counter: Debounce counter — 0=reset, 1/2/3 = count down 1/2/3 per step.
            filtered: True to evaluate on filtered data, False on unfiltered.
        """
        self._write_threshold(self._REG_LOW_TH, threshold_g)
        self._write_low_dur(duration_ms)
        self._write_low_hy(hysteresis_g)
        self._write_low_axis_enables(axes)
        self._write_filt_bit(self._REG_HIGH_LOW_INFO, self._LOW_FILT_BIT, filtered)
        self._write_debounce('lg', counter)
        self._enable_source(self.SOURCE_LOW_G)

    def set_high_g(self, threshold_g, duration_ms, hysteresis_g=0, axes=0x07, counter=0, filtered=True):
        """Configure the high-g (shock) interrupt and enable it."""
        self._write_threshold(self._REG_HIGH_TH, threshold_g)
        self._write_high_dur(duration_ms)
        self._write_high_hy(hysteresis_g)
        self._write_high_axis_enables(axes)
        self._write_filt_bit(self._REG_HIGH_LOW_INFO, self._HIGH_FILT_BIT, filtered)
        self._write_debounce('hg', counter)
        self._enable_source(self.SOURCE_HIGH_G)

    def set_slope(self, threshold_g, samples=1, axes=0x07, filtered=True):
        """Configure the slope interrupt and enable it (exclusive with alert)."""
        self._write_slope_threshold(threshold_g)
        self._write_slope_dur(samples)
        self._write_slope_axis_enables(axes)
        self._write_filt_bit(self._REG_SLOPE_TAPSENS, self._SLOPE_FILT_BIT, filtered)
        self._write_ctrl_reg3_bit(self._CTRL_REG3_SLOPE_INT, True)
        self._write_ctrl_reg3_bit(self._CTRL_REG3_SLOPE_ALERT, False)
        self._write_ctrl_reg3_bit(self._CTRL_REG3_ADV_INT, True)
        self._enable_source(self.SOURCE_SLOPE)

    def set_alert(self, enabled):
        """Enable alert mode (exclusive with slope). When enabled, slope criterion arms an alert phase instead of driving INT directly."""
        if enabled:
            self._enabled_sources &= ~self.SOURCE_SLOPE
            self._write_ctrl_reg3_bit(self._CTRL_REG3_SLOPE_INT, False)
            self._write_ctrl_reg3_bit(self._CTRL_REG3_SLOPE_ALERT, True)
            self._write_ctrl_reg3_bit(self._CTRL_REG3_ADV_INT, True)
            self._enable_source(self.SOURCE_ALERT)
        else:
            self._disable_source(self.SOURCE_ALERT)
            self._write_ctrl_reg3_bit(self._CTRL_REG3_SLOPE_ALERT, False)

    def set_tap(self, threshold_g, window_ms=250, axes=0x07, filtered=True):
        """Configure the double-tap interrupt and enable it."""
        self._write_slope_threshold(self._REG_TAPSENS_TH, threshold_g)
        self._write_tap_dur(window_ms)
        self._write_tap_axis_enables(axes)
        self._write_filt_bit(self._REG_SLOPE_TAPSENS, self._TAP_FILT_BIT, filtered)
        self._enable_source(self.SOURCE_TAP)

    def set_latch(self, enabled):
        """Enable latched interrupts (cleared by ``clear_interrupt()``)."""
        self._write_ctrl_reg3_bit(self._CTRL_REG3_LAT_INT, enabled)

    def clear_interrupt(self):
        """Clear latched interrupts (writes ``reset_int`` to CTRL_REG0)."""
        if self._sleeping:
            return
        ctrl0 = self._read_reg(self._REG_CTRL_REG0)
        self._write_reg(self._REG_CTRL_REG0, ctrl0 | self._CTRL_REG0_RESET_INT)

    def enable_interrupt(self, source):
        """Enable one interrupt source (e.g. ``SOURCE_LOW_G``)."""
        if source == self.SOURCE_NEW_DATA:
            self._write_ctrl_reg3_bit(self._CTRL_REG3_NEW_DATA_INT, True)
        else:
            self._write_ctrl_reg3_bit(self._CTRL_REG3_NEW_DATA_INT, False)
            # Slope and alert are mutually exclusive.
            if source == self.SOURCE_SLOPE:
                self._write_ctrl_reg3_bit(self._CTRL_REG3_SLOPE_ALERT, False)
                self._write_ctrl_reg3_bit(self._CTRL_REG3_SLOPE_INT, True)
                self._write_ctrl_reg3_bit(self._CTRL_REG3_ADV_INT, True)
                self._disable_source(self.SOURCE_ALERT)
            elif source == self.SOURCE_ALERT:
                self._write_ctrl_reg3_bit(self._CTRL_REG3_SLOPE_INT, False)
                self._write_ctrl_reg3_bit(self._CTRL_REG3_SLOPE_ALERT, True)
                self._write_ctrl_reg3_bit(self._CTRL_REG3_ADV_INT, True)
                self._disable_source(self.SOURCE_SLOPE)
            elif source == self.SOURCE_HIGH_G:
                self._write_ctrl_reg3_bit(self._CTRL_REG3_HIGH_INT, True)
            elif source == self.SOURCE_LOW_G:
                self._write_ctrl_reg3_bit(self._CTRL_REG3_LOW_INT, True)
            elif source == self.SOURCE_TAP:
                self._write_ctrl_reg3_bit(self._CTRL_REG3_TAP_INT, True)
        self._enable_source(source)

    def disable_interrupt(self, source):
        """Disable one interrupt source."""
        if source == self.SOURCE_NEW_DATA:
            self._write_ctrl_reg3_bit(self._CTRL_REG3_NEW_DATA_INT, False)
        elif source == self.SOURCE_SLOPE:
            self._write_ctrl_reg3_bit(self._CTRL_REG3_SLOPE_INT, False)
        elif source == self.SOURCE_ALERT:
            self._write_ctrl_reg3_bit(self._CTRL_REG3_SLOPE_ALERT, False)
            self._write_ctrl_reg3_bit(self._CTRL_REG3_ADV_INT, False)
        elif source == self.SOURCE_HIGH_G:
            self._write_ctrl_reg3_bit(self._CTRL_REG3_HIGH_INT, False)
        elif source == self.SOURCE_LOW_G:
            self._write_ctrl_reg3_bit(self._CTRL_REG3_LOW_INT, False)
        elif source == self.SOURCE_TAP:
            self._write_ctrl_reg3_bit(self._CTRL_REG3_TAP_INT, False)
        self._disable_source(source)

    def on_interrupt(self, callback):
        """Subscribe to INT line assertions.

        Args:
            callback: Function called with the STATUS_REG3 byte.
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
        """Read STATUS_REG3 (latched flags) without clearing them.

        Returns:
            int: STATUS_REG3 byte (bits 7..4 = latched sources; bits 2..0 = first axis).
        """
        return self._read_reg(self._REG_STATUS_REG3)

    def read_status(self):
        """Read all four status registers (1..4)."""
        s1 = self._read_reg(self._REG_STATUS_REG1)
        s2 = self._read_reg(self._REG_STATUS_REG2)
        s3 = self._read_reg(self._REG_STATUS_REG3)
        s4 = self._read_reg(self._REG_STATUS_REG4)
        return (s1, s2, s3, s4)

    def read_sign(self):
        """Decoded axis-sign bits of the last low/high/slope/tap event.

        Returns:
            dict: ``{'low': (x, y, z), 'high': (x, y, z), 'tapsens': (x, y, z)}``
            where each value is +1 or -1.
        """
        s2 = self._read_reg(self._REG_STATUS_REG2)
        s4 = self._read_reg(self._REG_STATUS_REG4)
        low_signs = (
            -1 if (s2 & 0x04) else +1,
            -1 if (s2 & 0x02) else +1,
            -1 if (s2 & 0x01) else +1,
        )
        high_signs = (
            -1 if (s4 & 0x80) else +1,
            -1 if (s4 & 0x40) else +1,
            -1 if (s4 & 0x20) else +1,
        )
        tapsens_signs = (
            -1 if (s4 & 0x10) else +1,
            -1 if (s4 & 0x08) else +1,
            -1 if (s4 & 0x04) else +1,
        )
        return {'low': low_signs, 'high': high_signs, 'tapsens': tapsens_signs}

    def set_wake_up(self, enabled, pause_ms=20):
        """Configure self-wake-up mode.

        Args:
            enabled: True to enter self-wake-up, False to leave.
            pause_ms: Sleep portion of the cycle in ms — 20, 80, 320 or 2560.
        """
        if pause_ms not in _WAKEUP_DUR_CODES:
            raise ValueError('pause_ms must be 20, 80, 320, or 2560')
        tcoy = self._read_reg(self._REG_TCO_Y)
        tcoy = (tcoy & ~self._TCO_Y_WAKE_MASK) | _WAKEUP_DUR_CODES[pause_ms]
        self._write_reg(self._REG_TCO_Y, tcoy)
        gz = self._read_reg(self._REG_GAIN_Z)
        if enabled:
            gz |= self._GAIN_Z_WAKE_UP
        else:
            gz &= ~self._GAIN_Z_WAKE_UP & 0xFF
        self._write_reg(self._REG_GAIN_Z, gz)

    def sleep(self):
        """Enter sleep mode. Most bus access is forbidden afterwards."""
        if self._sleeping:
            return
        ctrl0 = self._read_reg(self._REG_CTRL_REG0)
        self._write_reg(self._REG_CTRL_REG0, ctrl0 | self._CTRL_REG0_SLEEP)
        self._sleeping = True

    def wake(self):
        """Leave sleep mode. Waits 2 ms for the analog to settle."""
        if not self._sleeping:
            return
        ctrl0 = self._read_reg(self._REG_CTRL_REG0)
        self._write_reg(self._REG_CTRL_REG0, ctrl0 & ~self._CTRL_REG0_SLEEP & 0xFF)
        _delay_ms(2)
        self._sleeping = False

    def soft_reset(self):
        """Issue a power-on-equivalent reset; waits 30 ms; re-runs init config."""
        self._write_reg(self._REG_RESET, self._SOFT_RESET_CMD)
        _delay_ms(30)
        # Re-apply defaults.
        self._range_g = _DEFAULT_RANGE_G
        self._range_bits = _RANGE_BITS[_DEFAULT_RANGE_G]
        # CHIP_ID recheck.
        chip_id = self._read_reg(self._REG_CHIP_ID)
        if (chip_id & self._CHIP_ID_MASK) != self._CHIP_ID_VALUE:
            raise ValueError('BMA180 CHIP_ID after reset: expected 0x{:02X}, got 0x{:02X}'.format(
                self._CHIP_ID_VALUE, chip_id & self._CHIP_ID_MASK))
        # ee_w = 1.
        ctrl0 = self._read_reg(self._REG_CTRL_REG0)
        ctrl0 |= self._CTRL_REG0_EE_W
        self._write_reg(self._REG_CTRL_REG0, ctrl0)
        # range = ±2 g.
        olsb1 = self._read_reg(self._REG_OFFSET_LSB1)
        olsb1 = (olsb1 & ~self._OLSB1_RANGE_MASK) | self._range_bits
        self._write_reg(self._REG_OFFSET_LSB1, olsb1)
        # bw = 150 Hz.
        bw_code, _ = _nearest_bandwidth(_DEFAULT_BANDWIDTH_HZ)
        bw = self._read_reg(self._REG_BW_TCS)
        bw = (bw & 0x0F) | bw_code
        self._write_reg(self._REG_BW_TCS, bw)
        _delay_ms(4)
        self._sleeping = False

    def self_test(self):
        """Run the electrostatic self-test (datasheet st0 procedure).

        Returns True if every axis responds with > 200 LSB.

        Note:
            Reads soft data; a configuration change (range, bandwidth, etc.)
            is allowed. Soft-resets afterwards.
        """
        ctrl0 = self._read_reg(self._REG_CTRL_REG0)
        self._write_reg(self._REG_CTRL_REG0, ctrl0 | 0x04)  # st0 (bit 2)
        _delay_ms(10)
        # Read every axis.
        x_raw, y_raw, z_raw = self.read_raw()
        # Clear st0.
        self._write_reg(self._REG_CTRL_REG0, ctrl0)
        passed = (abs(x_raw) > 200) and (abs(y_raw) > 200) and (abs(z_raw) > 200)
        # Soft-reset to recover.
        self.soft_reset()
        return passed

    def calibrate_offset(self, axes=0x07, mode=1):
        """Run the chip's in-field zero-g calibration.

        Args:
            axes: Bitmask of axes to calibrate (bit 0 = X, bit 1 = Y, bit 2 = Z).
            mode: 0 = no calibration, 1 = fine, 2 = coarse, 3 = full offset
                calibration. Mode 3 disables low-g while in progress.
        """
        if mode not in (0, 1, 2, 3):
            raise ValueError('mode must be 0, 1, 2, or 3')
        # offset_finetuning = mode (bits 1:0 of CTRL_REG4 0x22).
        cr4 = self._read_reg(self._REG_CTRL_REG4)
        cr4 = (cr4 & 0xFC) | (mode & 0x03)
        self._write_reg(self._REG_CTRL_REG4, cr4)
        # Calibrate each axis by setting en_offset_x/y/z one at a time
        # (CTRL_REG1 0x0E bits 7,6,5), waiting for offset_st_s (STATUS_REG1 bit 1).
        ctrl1 = self._read_reg(self._REG_CTRL_REG1)
        axis_bits = (
            (0x80, axes & 0x01, 'x'),  # en_offset_x
            (0x40, axes & 0x02, 'y'),  # en_offset_y
            (0x20, axes & 0x04, 'z'),  # en_offset_z
        )
        for bit, enabled, _name in axis_bits:
            if not enabled:
                continue
            ctrl1 = self._read_reg(self._REG_CTRL_REG1)
            ctrl1 |= bit
            self._write_reg(self._REG_CTRL_REG1, ctrl1)
            # Wait for offset_st_s (bit 1 of STATUS_REG1).
            attempts = 0
            while attempts < 100:
                s1 = self._read_reg(self._REG_STATUS_REG1)
                if s1 & 0x02:
                    break
                _delay_ms(100)
                attempts += 1
            # Clear the en_offset bit.
            ctrl1 = self._read_reg(self._REG_CTRL_REG1)
            ctrl1 &= ~bit & 0xFF
            self._write_reg(self._REG_CTRL_REG1, ctrl1)
        # Restore offset_finetuning = 00 so low-g works again.
        cr4 = self._read_reg(self._REG_CTRL_REG4)
        cr4 &= 0xFC
        self._write_reg(self._REG_CTRL_REG4, cr4)

    def read_version(self):
        """Read the VERSION register split into (al_version, ml_version).

        Returns:
            tuple: (al_version, ml_version) — ``al_version`` is bits 7:4,
            ``ml_version`` is bits 3:0.
        """
        raw = self._read_reg(self._REG_VERSION)
        return ((raw >> 4) & 0x0F, raw & 0x0F)

    def read_customer(self, index):
        """Read one of the two free scratch bytes (CD1 / CD2)."""
        if index == 0:
            return self._read_reg(self._REG_CD1)
        if index == 1:
            return self._read_reg(self._REG_CD2)
        raise ValueError('index must be 0 or 1')

    def write_customer(self, index, value):
        """Write one of the two free scratch bytes."""
        if index == 0:
            self._write_reg(self._REG_CD1, value & 0xFF)
        elif index == 1:
            self._write_reg(self._REG_CD2, value & 0xFF)
        else:
            raise ValueError('index must be 0 or 1')

    # ---- internal helpers ------------------------------------------------

    def _write_threshold(self, reg, threshold_g):
        # 8 MSBs of |a|: code = round(threshold_g / range_g * 255).
        code = int(round(threshold_g / self._range_g * 255.0))
        code = max(0, min(255, code))
        self._write_reg(reg, code)

    def _write_slope_threshold(self, *args):
        # Accept either (reg, threshold_g) or (threshold_g,) when reg is fixed.
        if len(args) == 2:
            reg, threshold_g = args
        else:
            reg = self._REG_SLOPE_TH
            threshold_g = args[0]
        # 15.6 mg/LSB at ±2 g, scales with range: code = threshold_g / (0.0156 * range_g / 2).
        code = int(round(threshold_g / (0.0156 * self._range_g / 2.0)))
        code = max(0, min(255, code))
        self._write_reg(reg, code)

    def _write_low_dur(self, duration_ms):
        ld = self._read_reg(self._REG_LOW_DUR)
        code = max(0, min(127, int(round(duration_ms / _DUR_LSB_MS))))
        # low_dur<6:0> is bits 7:1; bit 0 (tco_range) is calibration, preserve.
        ld = (ld & 0x01) | ((code & 0x7F) << 1)
        self._write_reg(self._REG_LOW_DUR, ld)

    def _write_high_dur(self, duration_ms):
        hd = self._read_reg(self._REG_HIGH_DUR)
        code = max(0, min(127, int(round(duration_ms / _DUR_LSB_MS))))
        hd = (hd & 0x01) | ((code & 0x7F) << 1)
        self._write_reg(self._REG_HIGH_DUR, hd)

    def _write_low_hy(self, hysteresis_g):
        # low_hy<4:2> bits 2:0 of HY 0x23.
        code = max(0, min(31, int(round(hysteresis_g / self._range_g * 255.0 / 32.0))))
        low_hy_low3 = code & 0x07  # bits 2:0
        hy = self._read_reg(self._REG_HY)
        hy = (hy & ~self._HY_LOW_MASK) | low_hy_low3
        self._write_reg(self._REG_HY, hy)
        # low_hy<1:0> bits 7:6 of CTRL_REG4 0x22.
        low_hy_high2 = (code >> 3) & 0x03  # bits 1:0
        cr4 = self._read_reg(self._REG_CTRL_REG4)
        cr4 = (cr4 & ~(0x03 << self._CR4_LOW_HY_SHIFT)) | (low_hy_high2 << self._CR4_LOW_HY_SHIFT)
        self._write_reg(self._REG_CTRL_REG4, cr4)

    def _write_high_hy(self, hysteresis_g):
        # high_hy<4:0> bits 7:3 of HY 0x23.
        code = max(0, min(31, int(round(hysteresis_g / self._range_g * 255.0 / 32.0))))
        hy = self._read_reg(self._REG_HY)
        hy = (hy & 0x07) | ((code & 0x1F) << 3)
        self._write_reg(self._REG_HY, hy)

    def _write_low_axis_enables(self, axes):
        # bits 3:1 of HIGH_LOW_INFO (0x25), bit 0 is low_filt (preserve).
        hli = self._read_reg(self._REG_HIGH_LOW_INFO)
        hli = (hli & 0xF1) | ((axes & 0x07) << self._LOW_AXIS_SHIFT)
        self._write_reg(self._REG_HIGH_LOW_INFO, hli)

    def _write_high_axis_enables(self, axes):
        # bits 7:5 of HIGH_LOW_INFO (0x25), bit 4 is high_filt (preserve).
        hli = self._read_reg(self._REG_HIGH_LOW_INFO)
        hli = (hli & 0x0F) | ((axes & 0x07) << self._HIGH_AXIS_SHIFT)
        self._write_reg(self._REG_HIGH_LOW_INFO, hli)

    def _write_slope_axis_enables(self, axes):
        # bits 7:5 of SLOPE_TAPSENS_INFO (0x24), bit 4 is slope_filt (preserve).
        st = self._read_reg(self._REG_SLOPE_TAPSENS)
        st = (st & 0x0F) | ((axes & 0x07) << self._SLOPE_AXIS_SHIFT)
        self._write_reg(self._REG_SLOPE_TAPSENS, st)

    def _write_tap_axis_enables(self, axes):
        # bits 3:1 of SLOPE_TAPSENS_INFO (0x24), bit 0 is tapsens_filt (preserve).
        st = self._read_reg(self._REG_SLOPE_TAPSENS)
        st = (st & 0xF1) | ((axes & 0x07) << self._TAP_AXIS_SHIFT)
        self._write_reg(self._REG_SLOPE_TAPSENS, st)

    def _write_filt_bit(self, reg, bit, enabled):
        v = self._read_reg(reg)
        if enabled:
            v |= bit
        else:
            v &= ~bit & 0xFF
        self._write_reg(reg, v)

    def _write_debounce(self, kind, counter):
        if counter not in _DEBOUNCE_CODES:
            raise ValueError('counter must be 0, 1, 2, or 3')
        code = _DEBOUNCE_CODES[counter]
        cr4 = self._read_reg(self._REG_CTRL_REG4)
        if kind == 'lg':
            cr4 = (cr4 & ~(0x03 << self._CR4_FF_CD_SHIFT)) | (code & (0x03 << self._CR4_FF_CD_SHIFT))
        elif kind == 'hg':
            cr4 = (cr4 & ~(0x03 << self._CR4_MOT_CD_SHIFT)) | (code & (0x03 << self._CR4_MOT_CD_SHIFT))
        else:
            raise ValueError('kind must be "lg" or "hg"')
        self._write_reg(self._REG_CTRL_REG4, cr4)

    def _write_slope_dur(self, samples):
        if samples not in _SLOPE_DUR_CODES:
            raise ValueError('samples must be 1, 3, 5, or 7')
        tcox = self._read_reg(self._REG_TCO_X)
        tcox = (tcox & ~self._TCO_X_SLOPE_MASK) | _SLOPE_DUR_CODES[samples]
        self._write_reg(self._REG_TCO_X, tcox)

    def _write_tap_dur(self, window_ms):
        code, _ = _nearest_tap_dur(window_ms)
        gt = self._read_reg(self._REG_GAIN_T)
        gt = (gt & ~self._GAIN_T_TAP_MASK) | code
        self._write_reg(self._REG_GAIN_T, gt)

    def _write_ctrl_reg3_bit(self, bit, enabled):
        if self._sleeping:
            return
        cr3 = self._read_reg(self._REG_CTRL_REG3)
        if enabled:
            cr3 |= bit
        else:
            cr3 &= ~bit & 0xFF
        self._write_reg(self._REG_CTRL_REG3, cr3)

    def _enable_source(self, source):
        if self._sleeping:
            return
        self._enabled_sources |= source

    def _disable_source(self, source):
        self._enabled_sources &= ~source & 0xFF

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