"""VL53L1X — long-distance Time-of-Flight laser-ranging sensor (STMicroelectronics).

940 nm VCSEL emitter, 16x16 SPAD receiving array behind a lens and an
embedded ranging microcontroller measuring absolute distance up to 4 m at up
to 50 Hz. Two distance modes (short ~1.3 m, robust in sunlight; long ~3.6-4 m
in the dark), a 15-500 ms timing budget and a programmable region of
interest (4x4 to 16x16 SPADs). The datasheet has no register map: registers,
the default configuration block and all sequences follow ST's Ultra Lite
Driver (STSW-IMG009). Registers use a 16-bit index; multi-byte registers are
big-endian. The same driver file is used on MicroPython, CircuitPython, and
Linux hosts.

Pin-to-pin compatible with the VL53L0X and shares its family base
(_vl53_base) and public API shape: distance(), range_valid(),
start_continuous(), read_measurement(), thresholds, offset.

Multiple sensors on one bus: all sensors (VL53L0X and VL53L1X alike) power
up at 0x29. Hold every sensor's XSHUT low (each Connection's en_pin
disabled), then for each sensor in turn enable its XSHUT, construct a driver
on 0x29, call set_address(new), and build the real driver on a Connection at
the new address. The new address is volatile — it reverts to 0x29 on
power-up or an XSHUT low pulse.

Args:
    connection: Configured I2C connection pointing at the device (0x29).
        Its optional en_pin drives XSHUT (high = enabled), its optional
        int_pin receives GPIO1 (active low, open drain).
"""

from ._vl53_base import (
    _VL53Base, I2C_ADDRESS, SOURCE_LEVEL_LOW, SOURCE_LEVEL_HIGH,
    SOURCE_OUT_OF_WINDOW, SOURCE_NEW_SAMPLE_READY, SOURCE_IN_WINDOW)


# IDENTIFICATION__MODEL_ID + MODULE_TYPE read as one 16-bit word.
SENSOR_ID = 0xEACC
MODEL_ID = 0xEA
MODULE_TYPE = 0xCC

# Mapped range status meaning "range valid".
RANGE_STATUS_VALID = 0

# Distance modes for set_distance_mode / distance_mode.
DISTANCE_MODE_SHORT = 'short'
DISTANCE_MODE_LONG  = 'long'

_REG_I2C_SLAVE_DEVICE_ADDRESS = 0x0001
_REG_VHV_CONFIG_LOOP_BOUND    = 0x0008
_REG_VHV_INIT                 = 0x000B
_REG_XTALK_PLANE_OFFSET       = 0x0016
_REG_XTALK_X_GRADIENT         = 0x0018
_REG_XTALK_Y_GRADIENT         = 0x001A
_REG_PART_TO_PART_OFFSET      = 0x001E
_REG_MM_INNER_OFFSET          = 0x0020
_REG_MM_OUTER_OFFSET          = 0x0022
_REG_PAD_I2C_HV_EXTSUP        = 0x002E
_REG_GPIO_EXTSUP_HV           = 0x002F
_REG_GPIO_HV_MUX_CTRL         = 0x0030
_REG_GPIO_TIO_HV_STATUS       = 0x0031
_REG_INTERRUPT_CONFIG_GPIO    = 0x0046
_REG_PHASECAL_TIMEOUT         = 0x004B
_REG_RANGE_TIMEOUT_A          = 0x005E
_REG_RANGE_VCSEL_PERIOD_A     = 0x0060
_REG_RANGE_TIMEOUT_B          = 0x0061
_REG_RANGE_VCSEL_PERIOD_B     = 0x0063
_REG_SIGMA_THRESH             = 0x0064
_REG_MIN_COUNT_RATE_RTN_LIMIT = 0x0066
_REG_RANGE_VALID_PHASE_HIGH   = 0x0069
_REG_INTERMEASUREMENT_PERIOD  = 0x006C
_REG_THRESH_HIGH              = 0x0072
_REG_THRESH_LOW               = 0x0074
_REG_SD_WOI_SD0               = 0x0078
_REG_SD_INITIAL_PHASE_SD0     = 0x007A
_REG_ROI_CENTRE_SPAD          = 0x007F
_REG_ROI_XY_SIZE              = 0x0080
_REG_INTERRUPT_CLEAR          = 0x0086
_REG_MODE_START               = 0x0087
_REG_RESULT_RANGE_STATUS      = 0x0089
_REG_OSC_CALIBRATE_VAL        = 0x00DE
_REG_FIRMWARE_SYSTEM_STATUS   = 0x00E5
_REG_MODEL_ID                 = 0x010F
_REG_MODULE_TYPE              = 0x0110
_REG_REVISION_ID              = 0x0111
_REG_MODE_ROI_CENTRE_SPAD     = 0x013E

_DEFAULT_CONFIG_START = 0x002D

# ULD VL51L1X_DEFAULT_CONFIGURATION — opaque, written verbatim to
# 0x002D..0x0087, one byte per register.
_DEFAULT_CONFIGURATION = (
    0x00, 0x00, 0x00, 0x01, 0x02, 0x00, 0x02, 0x08, 0x00, 0x08, 0x10, 0x01, 0x01, 0x00, 0x00, 0x00,
    0x00, 0xFF, 0x00, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x0B, 0x00, 0x00, 0x02, 0x0A, 0x21,
    0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00, 0xC8, 0x00, 0x00, 0x38, 0xFF, 0x01, 0x00, 0x08, 0x00,
    0x00, 0x01, 0xCC, 0x0F, 0x01, 0xF1, 0x0D, 0x01, 0x68, 0x00, 0x80, 0x08, 0xB8, 0x00, 0x00, 0x00,
    0x00, 0x0F, 0x89, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x0F, 0x0D, 0x0E, 0x0E, 0x00,
    0x00, 0x02, 0xC7, 0xFF, 0x9B, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
)

# ULD status_rtn: raw RESULT__RANGE_STATUS (bits 4:0) -> mapped range status.
_STATUS_MAP = (255, 255, 255, 5, 2, 4, 1, 7, 3, 0, 255, 255, 9, 13, 255, 255,
               255, 255, 10, 6, 255, 255, 11, 12)

# Timing budget (ms) -> (RANGE_CONFIG__TIMEOUT_MACROP_A, _B) per distance mode.
_BUDGET_SHORT = {
    15: (0x001D, 0x0027), 20: (0x0051, 0x006E), 33: (0x00D6, 0x006E), 50: (0x01AE, 0x01E8),
    100: (0x02E1, 0x0388), 200: (0x03E1, 0x0496), 500: (0x0591, 0x05C1),
}
_BUDGET_LONG = {
    20: (0x001E, 0x0022), 33: (0x0060, 0x006E), 50: (0x00AD, 0x00C6),
    100: (0x01CC, 0x01EA), 200: (0x02D9, 0x02F8), 500: (0x048F, 0x04A4),
}

# Distance mode -> (PHASECAL timeout, VCSEL period A, VCSEL period B,
# VALID_PHASE_HIGH, WOI_SD0, INITIAL_PHASE_SD0).
_DISTANCE_MODES = {
    DISTANCE_MODE_SHORT: (0x14, 0x07, 0x05, 0x38, 0x0705, 0x0606),
    DISTANCE_MODE_LONG:  (0x0A, 0x0F, 0x0D, 0xB8, 0x0F0D, 0x0E0E),
}

# Logical SOURCE_* -> SYSTEM__INTERRUPT_CONFIG_GPIO value.
_SOURCE_TO_CONFIG = {
    SOURCE_LEVEL_LOW: 0x00,
    SOURCE_LEVEL_HIGH: 0x01,
    SOURCE_OUT_OF_WINDOW: 0x02,
    SOURCE_NEW_SAMPLE_READY: 0x20,
    SOURCE_IN_WINDOW: 0x03,
}
# Window mode (bits 1:0) -> logical SOURCE_*.
_WINDOW_TO_SOURCE = (SOURCE_LEVEL_LOW, SOURCE_LEVEL_HIGH, SOURCE_OUT_OF_WINDOW, SOURCE_IN_WINDOW)

_CALIBRATION_SAMPLES = 50


def _round(value):
    return int(value + 0.5) if value >= 0 else -int(-value + 0.5)


class VL53L1XMinimal(_VL53Base):
    """VL53L1X Time-of-Flight ranging sensor — minimal interface.

    Runs the full initialization sequence at construction (boot wait,
    firmware boot poll, sensor ID check, ULD default configuration, 2V8 I/O
    mode, GPIO1 active low, settling ranging) and leaves the chip idle in
    long distance mode with a 100 ms timing budget. distance() then takes
    one single-shot measurement per call.

    Args:
        connection: Configured I2C connection pointing at the device.

    Raises:
        ValueError: If the model ID / module type word is not 0xEACC.
        OSError: If a poll loop times out (500 ms).
    """

    def __init__(self, connection):
        super().__init__(connection, 2, 'VL53L1X')
        self._range_status = 255
        self._init()

    # --- Initialization ---------------------------------------------------

    def _init(self):
        self._boot_wait()
        self._wait_until(lambda: self._rd8(_REG_FIRMWARE_SYSTEM_STATUS) & 0x01, 'boot')

        sensor_id = self._rd16(_REG_MODEL_ID)
        if sensor_id != SENSOR_ID:
            raise ValueError('VL53L1X not found: expected sensor ID 0x{:04X}, got 0x{:04X}'.format(
                SENSOR_ID, sensor_id))

        for i, value in enumerate(_DEFAULT_CONFIGURATION):
            self._wr8(_DEFAULT_CONFIG_START + i, value)

        # 2V8 I/O mode for I2C and GPIO1 pads; GPIO1 active low.
        self._wr8(_REG_PAD_I2C_HV_EXTSUP, 0x01)
        self._wr8(_REG_GPIO_EXTSUP_HV, 0x01)
        self._wr8(_REG_GPIO_HV_MUX_CTRL, 0x11)

        # Settling ranging (ULD SensorInit), then two-bound VHV from the
        # previous temperature.
        self._wr8(_REG_MODE_START, 0x40)
        self._wait_until(self._data_ready, 'data ready')
        self._wr8(_REG_INTERRUPT_CLEAR, 0x01)
        self._wr8(_REG_MODE_START, 0x00)
        self._wr8(_REG_VHV_CONFIG_LOOP_BOUND, 0x09)
        self._wr8(_REG_VHV_INIT, 0x00)

    # --- Ranging ----------------------------------------------------------

    def _data_ready(self):
        # GPIO1 is active low: line asserted (bit 0 == 0) means data ready.
        return (self._rd8(_REG_GPIO_TIO_HV_STATUS) & 0x01) == 0

    def _read_result(self):
        data = self._rd_block(_REG_RESULT_RANGE_STATUS, 17)
        self._wr8(_REG_INTERRUPT_CLEAR, 0x01)
        raw = data[0] & 0x1F
        self._range_status = _STATUS_MAP[raw] if raw < len(_STATUS_MAP) else 255
        return data

    def _wait_and_read(self):
        self._wait_until(self._data_ready, 'data ready')
        data = self._read_result()
        return (data[13] << 8) | data[14]

    def distance(self):
        """Take one single-shot measurement.

        Blocks for about one timing budget (100 ms by default). Returns the
        raw range even when the measurement is not valid; check
        range_valid().

        Returns:
            int: Distance in mm.

        Raises:
            OSError: If the measurement does not complete within 500 ms.
        """
        with self._lock:
            self._wr8(_REG_INTERRUPT_CLEAR, 0x01)
            self._wr8(_REG_MODE_START, 0x10)
            return self._wait_and_read()

    def range_valid(self):
        """Report whether the most recent measurement was valid.

        Returns:
            bool: True iff the mapped range status was 0 (range valid).
        """
        return self._range_status == RANGE_STATUS_VALID

    def _active_source(self):
        value = self._rd8(_REG_INTERRUPT_CONFIG_GPIO)
        if value & 0x20:
            return SOURCE_NEW_SAMPLE_READY
        return _WINDOW_TO_SOURCE[value & 0x03]

    def _poll_interrupt_status(self):
        with self._lock:
            if not self._data_ready():
                return 0
            self._wr8(_REG_INTERRUPT_CLEAR, 0x01)
            return self._active_source()


class VL53L1XFull(VL53L1XMinimal):
    """VL53L1X full interface — extends Minimal with timed continuous
    ranging, the full measurement record, distance mode, timing budget,
    inter-measurement period, signal and sigma thresholds, region of
    interest, offset and crosstalk compensation with calibration helpers,
    temperature update, address change, distance thresholds,
    identification, and the Level-2 interrupt API.

    Args:
        connection: Configured I2C connection pointing at the device.

    Raises:
        ValueError: If the model ID / module type word is not 0xEACC.
        OSError: If a poll loop times out (500 ms).
    """

    # --- Continuous ranging -----------------------------------------------

    def start_continuous(self, period_ms=0):
        """Start timed continuous ranging.

        Args:
            period_ms: Inter-measurement period in ms, 0 to 60000. 0 (and any
                value below the timing budget) runs at the timing budget,
                i.e. back-to-back — the chip requires period >= budget.

        Raises:
            ValueError: If period_ms is out of range.
        """
        if period_ms < 0 or period_ms > 60000:
            raise ValueError('period must be 0 to 60000 ms')
        with self._lock:
            period = max(period_ms, self.timing_budget() // 1000, 1)
            self.set_inter_measurement(period)
            self._wr8(_REG_INTERRUPT_CLEAR, 0x01)
            self._wr8(_REG_MODE_START, 0x40)

    def stop_continuous(self):
        """Stop continuous ranging. Does not wait for a running measurement."""
        self._wr8(_REG_MODE_START, 0x00)

    def read_continuous(self):
        """Wait for the next continuous-mode result and read it.

        Returns:
            int: Distance in mm (check range_valid()).

        Raises:
            OSError: If no result arrives within 500 ms.
        """
        with self._lock:
            return self._wait_and_read()

    def data_ready(self):
        """Report whether a measurement is pending (non-blocking).

        Returns:
            bool: True if GPIO__TIO_HV_STATUS shows the GPIO1 line asserted.
        """
        return self._data_ready()

    def read_measurement(self):
        """Read the full result block and clear the interrupt (non-blocking).

        Returns:
            dict: distance_mm (int), range_status (int), signal_rate_mcps
            (float), ambient_rate_mcps (float), effective_spad_count (float).
        """
        with self._lock:
            data = self._read_result()
            return {
                'distance_mm': (data[13] << 8) | data[14],
                'range_status': self._range_status,
                'signal_rate_mcps': ((data[15] << 8) | data[16]) / 128,
                'ambient_rate_mcps': ((data[7] << 8) | data[8]) / 128,
                'effective_spad_count': ((data[3] << 8) | data[4]) / 256,
            }

    def range_status(self):
        """Mapped range status of the most recent measurement.

        Returns:
            int: 0 = valid, 1 = sigma fail, 2 = signal fail, 4 = out of
            bounds, 7 = wrap-around, 255 = no update; see the spec table.
        """
        return self._range_status

    # --- Timing and distance mode -----------------------------------------

    def set_timing_budget(self, budget_us):
        """Set the per-measurement timing budget (ULD table values only).

        Args:
            budget_us: 15000 (short mode only), 20000, 33000, 50000, 100000,
                200000 or 500000.

        Raises:
            ValueError: If the budget is not in the table for the current
                distance mode.
        """
        with self._lock:
            table = _BUDGET_SHORT if self.distance_mode() == DISTANCE_MODE_SHORT else _BUDGET_LONG
            if budget_us % 1000 or (budget_us // 1000) not in table:
                raise ValueError('timing budget must be one of {} us in this distance mode'.format(
                    sorted(ms * 1000 for ms in table)))
            timeout_a, timeout_b = table[budget_us // 1000]
            self._wr16(_REG_RANGE_TIMEOUT_A, timeout_a)
            self._wr16(_REG_RANGE_TIMEOUT_B, timeout_b)

    def timing_budget(self):
        """Decode the timing budget from RANGE_CONFIG__TIMEOUT_MACROP_A.

        Returns:
            int: Budget in µs, or 0 if the register holds no table value.
        """
        timeout_a = self._rd16(_REG_RANGE_TIMEOUT_A)
        for table in (_BUDGET_SHORT, _BUDGET_LONG):
            for ms, (a, _) in table.items():
                if a == timeout_a:
                    return ms * 1000
        return 0

    def set_distance_mode(self, mode):
        """Select short or long distance mode, keeping the timing budget.

        Short (~1.3 m) is robust against ambient light; long (up to 4 m in
        the dark) is the default. If the current budget is unknown, 100 ms
        is applied.

        Args:
            mode: DISTANCE_MODE_SHORT ('short') or DISTANCE_MODE_LONG ('long').

        Raises:
            ValueError: If mode is unknown, or switching to long while the
                timing budget is 15 ms.
        """
        if mode not in _DISTANCE_MODES:
            raise ValueError("mode must be 'short' or 'long'")
        with self._lock:
            budget = self.timing_budget() or 100000
            if mode == DISTANCE_MODE_LONG and budget == 15000:
                raise ValueError('15 ms timing budget is only available in short distance mode')
            phasecal, vcsel_a, vcsel_b, phase_high, woi, initial_phase = _DISTANCE_MODES[mode]
            self._wr8(_REG_PHASECAL_TIMEOUT, phasecal)
            self._wr8(_REG_RANGE_VCSEL_PERIOD_A, vcsel_a)
            self._wr8(_REG_RANGE_VCSEL_PERIOD_B, vcsel_b)
            self._wr8(_REG_RANGE_VALID_PHASE_HIGH, phase_high)
            self._wr16(_REG_SD_WOI_SD0, woi)
            self._wr16(_REG_SD_INITIAL_PHASE_SD0, initial_phase)
            self.set_timing_budget(budget)

    def distance_mode(self):
        """Read the current distance mode.

        Returns:
            str: 'short' or 'long'.

        Raises:
            ValueError: If PHASECAL_CONFIG__TIMEOUT_MACROP holds neither mode's value.
        """
        value = self._rd8(_REG_PHASECAL_TIMEOUT)
        if value == 0x14:
            return DISTANCE_MODE_SHORT
        if value == 0x0A:
            return DISTANCE_MODE_LONG
        raise ValueError('unknown distance mode register value 0x{:02X}'.format(value))

    def set_inter_measurement(self, period_ms):
        """Set the continuous-mode inter-measurement period.

        Should be >= the timing budget (start_continuous enforces this).

        Args:
            period_ms: Period in ms, 1 to 60000.

        Raises:
            ValueError: If out of range.
        """
        if period_ms < 1 or period_ms > 60000:
            raise ValueError('inter-measurement period must be 1 to 60000 ms')
        clock_pll = self._rd16(_REG_OSC_CALIBRATE_VAL) & 0x03FF
        self._wr32(_REG_INTERMEASUREMENT_PERIOD, (clock_pll * period_ms * 1075) // 1000)

    def inter_measurement(self):
        """Read the continuous-mode inter-measurement period.

        Returns:
            int: Period in ms (0 if the oscillator calibration reads 0).
        """
        clock_pll = self._rd16(_REG_OSC_CALIBRATE_VAL) & 0x03FF
        if clock_pll == 0:
            return 0
        return (self._rd32(_REG_INTERMEASUREMENT_PERIOD) * 1000) // (clock_pll * 1075)

    # --- Signal and sigma thresholds --------------------------------------

    def set_signal_rate_limit(self, limit_mcps):
        """Set the minimum return signal rate for a valid result.

        Args:
            limit_mcps: Limit in MCPS, 0 to 511.99 (default 1.0).

        Raises:
            ValueError: If out of range.
        """
        if limit_mcps < 0 or limit_mcps > 511.99:
            raise ValueError('signal rate limit must be 0 to 511.99 MCPS')
        self._wr16(_REG_MIN_COUNT_RATE_RTN_LIMIT, int(limit_mcps * 128 + 0.5))

    def signal_rate_limit(self):
        """Read the minimum return signal rate.

        Returns:
            float: Limit in MCPS.
        """
        return self._rd16(_REG_MIN_COUNT_RATE_RTN_LIMIT) / 128

    def set_sigma_threshold(self, sigma_mm):
        """Set the maximum estimated standard deviation for a valid result.

        Args:
            sigma_mm: Threshold in mm, 0 to 16383 (default 90).

        Raises:
            ValueError: If out of range.
        """
        if sigma_mm < 0 or sigma_mm > 16383:
            raise ValueError('sigma threshold must be 0 to 16383 mm')
        self._wr16(_REG_SIGMA_THRESH, int(sigma_mm) << 2)

    def sigma_threshold(self):
        """Read the sigma threshold.

        Returns:
            int: Threshold in mm.
        """
        return self._rd16(_REG_SIGMA_THRESH) >> 2

    # --- Region of interest -----------------------------------------------

    def set_roi(self, width, height):
        """Set the receiving region-of-interest size.

        Sizes above 10 SPADs re-centre the ROI on SPAD 199 (array centre) so
        it stays on the array.

        Args:
            width: ROI width in SPADs, 4 to 16.
            height: ROI height in SPADs, 4 to 16.

        Raises:
            ValueError: If out of range.
        """
        if not (4 <= width <= 16 and 4 <= height <= 16):
            raise ValueError('ROI width and height must be 4 to 16 SPADs')
        with self._lock:
            if width > 10 or height > 10:
                self._wr8(_REG_ROI_CENTRE_SPAD, 199)
            self._wr8(_REG_ROI_XY_SIZE, ((height - 1) << 4) | (width - 1))

    def roi(self):
        """Read the region-of-interest size.

        Returns:
            tuple: (width: int, height: int) in SPADs.
        """
        value = self._rd8(_REG_ROI_XY_SIZE)
        return (value & 0x0F) + 1, (value >> 4) + 1

    def set_roi_center(self, spad):
        """Move the region of interest to a centre SPAD.

        Args:
            spad: SPAD number 0-255 per ST UM2555 numbering (199 = array
                centre). The caller keeps the ROI inside the array.

        Raises:
            ValueError: If out of range.
        """
        if spad < 0 or spad > 255:
            raise ValueError('ROI centre SPAD must be 0 to 255')
        self._wr8(_REG_ROI_CENTRE_SPAD, spad)

    def roi_center(self):
        """Read the region-of-interest centre SPAD.

        Returns:
            int: SPAD number.
        """
        return self._rd8(_REG_ROI_CENTRE_SPAD)

    def optical_center(self):
        """Read the factory-measured optical-centre SPAD from NVM.

        Returns:
            int: SPAD number; pass to set_roi_center() to align the ROI
            with this part's lens.
        """
        return self._rd8(_REG_MODE_ROI_CENTRE_SPAD)

    # --- Offset and crosstalk ---------------------------------------------

    def set_offset(self, offset_mm):
        """Override the part-to-part range offset (volatile).

        Args:
            offset_mm: Offset in mm, -1024.0 to 1023.75 (0.25 mm steps).

        Raises:
            ValueError: If out of range.
        """
        if offset_mm < -1024.0 or offset_mm > 1023.75:
            raise ValueError('offset must be -1024.0 to 1023.75 mm')
        with self._lock:
            self._wr16(_REG_PART_TO_PART_OFFSET, _round(offset_mm * 4) & 0x1FFF)
            self._wr16(_REG_MM_INNER_OFFSET, 0)
            self._wr16(_REG_MM_OUTER_OFFSET, 0)

    def offset(self):
        """Read the part-to-part range offset.

        Returns:
            float: Offset in mm.
        """
        raw = self._rd16(_REG_PART_TO_PART_OFFSET) & 0x1FFF
        if raw & 0x1000:
            raw -= 0x2000
        return raw * 0.25

    def set_crosstalk_compensation(self, rate_mcps):
        """Set the per-SPAD crosstalk compensation rate (volatile).

        Args:
            rate_mcps: 0 disables; otherwise 0 < rate < 0.128 MCPS.

        Raises:
            ValueError: If out of range.
        """
        if rate_mcps < 0 or rate_mcps >= 0.128:
            raise ValueError('crosstalk rate must be 0 (off) or below 0.128 MCPS')
        with self._lock:
            self._wr16(_REG_XTALK_X_GRADIENT, 0)
            self._wr16(_REG_XTALK_Y_GRADIENT, 0)
            self._wr16(_REG_XTALK_PLANE_OFFSET, min(int(rate_mcps * 512000 + 0.5), 0xFFFF))

    def crosstalk_compensation(self):
        """Read the per-SPAD crosstalk compensation rate.

        Returns:
            float: Rate in MCPS.
        """
        return self._rd16(_REG_XTALK_PLANE_OFFSET) / 512000

    def _collect(self, count):
        """Range `count` timed-mode samples; return list of result blocks."""
        samples = []
        self._wr8(_REG_INTERRUPT_CLEAR, 0x01)
        self._wr8(_REG_MODE_START, 0x40)
        try:
            for _ in range(count):
                self._wait_until(self._data_ready, 'data ready')
                samples.append(self._read_result())
        finally:
            self._wr8(_REG_MODE_START, 0x00)
        return samples

    def calibrate_offset(self, target_mm):
        """Measure and apply the range offset against a target at a known
        distance (ULD CalibrateOffset; ST recommends 88 % white at 140 mm).

        Ranges 50 times with the offset zeroed. Must not be called while
        ranging. Store the returned value and re-apply it with set_offset()
        after each power-up.

        Args:
            target_mm: True target distance in mm.

        Returns:
            float: Applied offset in mm (target - mean measured distance).

        Raises:
            ValueError: If the resulting offset is out of range.
            OSError: If a result does not arrive within 500 ms.
        """
        with self._lock:
            self._wr16(_REG_PART_TO_PART_OFFSET, 0)
            self._wr16(_REG_MM_INNER_OFFSET, 0)
            self._wr16(_REG_MM_OUTER_OFFSET, 0)
            samples = self._collect(_CALIBRATION_SAMPLES)
            mean = sum((d[13] << 8) | d[14] for d in samples) / len(samples)
            offset = target_mm - mean
            self.set_offset(offset)
            return offset

    def calibrate_crosstalk(self, target_mm):
        """Measure and apply crosstalk compensation for a cover glass (ULD
        CalibrateXtalk; ST uses a 17 % grey target at the distance where the
        sensor starts to under-range).

        Ranges 50 times with compensation off. Must not be called while
        ranging. Store the returned value and re-apply it with
        set_crosstalk_compensation() after each power-up.

        Args:
            target_mm: True target distance in mm (> 0).

        Returns:
            float: Applied per-SPAD crosstalk rate in MCPS (0 to 0.127).

        Raises:
            ValueError: If target_mm is not positive.
            OSError: If a result does not arrive within 500 ms.
        """
        if target_mm <= 0:
            raise ValueError('target distance must be positive')
        with self._lock:
            self._wr16(_REG_XTALK_PLANE_OFFSET, 0)
            samples = self._collect(_CALIBRATION_SAMPLES)
            n = len(samples)
            mean_distance = sum((d[13] << 8) | d[14] for d in samples) / n
            mean_signal = sum(((d[15] << 8) | d[16]) / 128 for d in samples) / n
            mean_spads = sum(((d[3] << 8) | d[4]) / 256 for d in samples) / n
            rate = 0.0
            if mean_spads > 0:
                rate = mean_signal * (1 - mean_distance / target_mm) / mean_spads
            rate = min(max(rate, 0.0), 0.127)
            self.set_crosstalk_compensation(rate)
            return rate

    def recalibrate(self):
        """Run the temperature update (ULD StartTemperatureUpdate).

        Call in software standby (not while ranging), after the temperature
        changes by more than about 8 °C.

        Raises:
            OSError: If the update ranging times out.
        """
        with self._lock:
            self._wr8(_REG_VHV_CONFIG_LOOP_BOUND, 0x81)
            self._wr8(_REG_VHV_INIT, 0x92)
            self._wr8(_REG_MODE_START, 0x40)
            self._wait_until(self._data_ready, 'temperature update')
            self._wr8(_REG_INTERRUPT_CLEAR, 0x01)
            self._wr8(_REG_MODE_START, 0x00)
            self._wr8(_REG_VHV_CONFIG_LOOP_BOUND, 0x09)
            self._wr8(_REG_VHV_INIT, 0x00)

    # --- Address, thresholds, identification ------------------------------

    def set_address(self, address):
        """Change the chip's I2C address (volatile).

        The chip answers on the new address immediately; this driver
        instance becomes unusable. Construct a new Connection at the new
        address and a new driver.

        Args:
            address: New 7-bit address, 0x08-0x77.

        Raises:
            ValueError: If out of range.
        """
        self._set_address_reg(_REG_I2C_SLAVE_DEVICE_ADDRESS, address)

    def set_interrupt_thresholds(self, low_mm, high_mm):
        """Set the distance thresholds used by the threshold interrupt sources.

        Args:
            low_mm: Low threshold in mm.
            high_mm: High threshold in mm, low_mm <= high_mm <= 65535.

        Raises:
            ValueError: If out of range.
        """
        if low_mm < 0 or high_mm < low_mm or high_mm > 65535:
            raise ValueError('thresholds must satisfy 0 <= low <= high <= 65535 mm')
        with self._lock:
            self._wr16(_REG_THRESH_HIGH, high_mm)
            self._wr16(_REG_THRESH_LOW, low_mm)

    def interrupt_thresholds(self):
        """Read the distance thresholds.

        Returns:
            tuple: (low_mm: int, high_mm: int).
        """
        return self._rd16(_REG_THRESH_LOW), self._rd16(_REG_THRESH_HIGH)

    def model_id(self):
        """Read IDENTIFICATION__MODEL_ID.

        Returns:
            int: 0xEA.
        """
        return self._rd8(_REG_MODEL_ID)

    def module_type(self):
        """Read IDENTIFICATION__MODULE_TYPE.

        Returns:
            int: 0xCC.
        """
        return self._rd8(_REG_MODULE_TYPE)

    def revision_id(self):
        """Read IDENTIFICATION__REVISION_ID (mask revision).

        Returns:
            int: 0x10.
        """
        return self._rd8(_REG_REVISION_ID)

    # --- Interrupt API ----------------------------------------------------

    def enable_interrupt(self, source):
        """Select the GPIO1 interrupt source (replaces the active one).

        With a threshold source active, data_ready()/read_continuous() only
        see a pending result when the threshold condition is met.

        Args:
            source: One of the SOURCE_* constants (1-5).

        Raises:
            ValueError: If source is not a SOURCE_* constant.
        """
        if source not in _SOURCE_TO_CONFIG:
            raise ValueError('source must be one of the SOURCE_* constants')
        self._wr8(_REG_INTERRUPT_CONFIG_GPIO, _SOURCE_TO_CONFIG[source])

    def disable_interrupt(self, source):
        """Revert to SOURCE_NEW_SAMPLE_READY if source is the active
        threshold source. The chip has no disabled state, so disabling
        SOURCE_NEW_SAMPLE_READY is a no-op; use off_interrupt() to stop
        callbacks.

        Args:
            source: One of the SOURCE_* constants.
        """
        with self._lock:
            if source != SOURCE_NEW_SAMPLE_READY and self._active_source() == source:
                self._wr8(_REG_INTERRUPT_CONFIG_GPIO, 0x20)

    def poll_interrupt(self):
        """Read and clear a pending interrupt.

        Returns:
            int: The active SOURCE_* value if GPIO1 is asserted, else 0.
        """
        return self._poll_interrupt_status()

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to GPIO1 interrupt events.

        Delivery: if int_pin is given, it is wired directly. Otherwise falls
        back to connection.int_pin (falling edge, GPIO1 is active low), or a
        5 ms polling thread on Linux if neither is available. The driver
        clears the interrupt before invoking the callback; the polling
        fallback consumes results, so don't mix it with read_continuous().

        Args:
            callback: Callable(status: int) — the active SOURCE_* value.
            int_pin: Optional InputPin for this call, overriding connection.int_pin.
        """
        self._subscribe(callback, int_pin)

    def off_interrupt(self):
        """Unsubscribe and stop delivery."""
        self._unsubscribe()
