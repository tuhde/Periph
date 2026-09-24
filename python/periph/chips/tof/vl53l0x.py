"""VL53L0X — Time-of-Flight laser-ranging sensor (STMicroelectronics).

940 nm VCSEL emitter, SPAD receiving array and an embedded ranging
microcontroller measuring absolute distance up to ~2 m, largely independent
of target reflectance. The datasheet has no register map: registers, the
tuning table and the init/calibration sequences follow ST's STSW-IMG005 API
(the same derivation as Pololu's VL53L0X library). Multi-byte registers are
big-endian. The same driver file is used on MicroPython, CircuitPython, and
Linux hosts.

Multiple sensors on one bus: all sensors power up at 0x29. Hold every
sensor's XSHUT low (each Connection's en_pin disabled), then for each sensor
in turn enable its XSHUT, construct a driver on 0x29, call
set_address(new), and build the real driver on a Connection at the new
address. The new address is volatile — it reverts to 0x29 on power-up or an
XSHUT low pulse.

Args:
    connection: Configured I2C connection pointing at the device (0x29).
        Its optional en_pin drives XSHUT (high = enabled), its optional
        int_pin receives GPIO1 (active low, open drain).
"""

from ._vl53_base import (
    _VL53Base, I2C_ADDRESS, SOURCE_LEVEL_LOW,
    SOURCE_LEVEL_HIGH, SOURCE_OUT_OF_WINDOW, SOURCE_NEW_SAMPLE_READY)


MODEL_ID = 0xEE

# Interrupt sources (SOURCE_*, re-exported from the family base) equal the
# SYSTEM_INTERRUPT_CONFIG_GPIO values 1-4 (mutually exclusive).

# VCSEL period types for set_vcsel_pulse_period / vcsel_pulse_period.
PRE_RANGE   = 'pre_range'
FINAL_RANGE = 'final_range'

# Ranging profiles for set_profile.
PROFILE_DEFAULT       = 'default'
PROFILE_LONG_RANGE    = 'long_range'
PROFILE_HIGH_SPEED    = 'high_speed'
PROFILE_HIGH_ACCURACY = 'high_accuracy'

# Device range status meaning "range complete — valid".
RANGE_STATUS_VALID = 11

_REG_SYSRANGE_START            = 0x00
_REG_SYSTEM_SEQUENCE_CONFIG    = 0x01
_REG_SYSTEM_INTERMEASUREMENT   = 0x04
_REG_SYSTEM_INTERRUPT_CONFIG   = 0x0A
_REG_SYSTEM_INTERRUPT_CLEAR    = 0x0B
_REG_SYSTEM_THRESH_HIGH        = 0x0C
_REG_SYSTEM_THRESH_LOW         = 0x0E
_REG_RESULT_INTERRUPT_STATUS   = 0x13
_REG_RESULT_RANGE_STATUS       = 0x14
_REG_CROSSTALK_COMPENSATION    = 0x20
_REG_PART_TO_PART_RANGE_OFFSET = 0x28
_REG_PHASECAL_CONFIG_TIMEOUT   = 0x30
_REG_GLOBAL_CONFIG_VCSEL_WIDTH = 0x32
_REG_FINAL_MIN_COUNT_RATE_RTN  = 0x44
_REG_MSRC_CONFIG_TIMEOUT       = 0x46
_REG_FINAL_VALID_PHASE_LOW     = 0x47
_REG_FINAL_VALID_PHASE_HIGH    = 0x48
_REG_DYNAMIC_SPAD_NUM_REQ      = 0x4E
_REG_DYNAMIC_SPAD_START_OFFSET = 0x4F
_REG_PRE_RANGE_VCSEL_PERIOD    = 0x50
_REG_PRE_RANGE_TIMEOUT         = 0x51
_REG_PRE_VALID_PHASE_LOW       = 0x56
_REG_PRE_VALID_PHASE_HIGH      = 0x57
_REG_MSRC_CONFIG_CONTROL       = 0x60
_REG_FINAL_RANGE_VCSEL_PERIOD  = 0x70
_REG_FINAL_RANGE_TIMEOUT       = 0x71
_REG_POWER_FORCE               = 0x80
_REG_GPIO_HV_MUX_ACTIVE_HIGH   = 0x84
_REG_I2C_MODE                  = 0x88
_REG_VHV_PAD_EXTSUP_HV         = 0x89
_REG_I2C_SLAVE_DEVICE_ADDRESS  = 0x8A
_REG_STOP_VARIABLE             = 0x91
_REG_SPAD_ENABLES_REF_0        = 0xB0
_REG_REF_EN_START_SELECT       = 0xB6
_REG_MODEL_ID                  = 0xC0
_REG_REVISION_ID               = 0xC2
_REG_OSC_CALIBRATE_VAL         = 0xF8
_REG_PAGE_SELECT               = 0xFF

_SEQ_TCC         = 0x10
_SEQ_DSS         = 0x08
_SEQ_MSRC        = 0x04
_SEQ_PRE_RANGE   = 0x40
_SEQ_FINAL_RANGE = 0x80
_SEQ_OPERATING   = 0xE8

_MIN_TIMING_BUDGET_US = 20000

# Timing-budget overheads, µs.
_START_OVERHEAD       = 1910
_END_OVERHEAD         = 960
_MSRC_OVERHEAD        = 660
_TCC_OVERHEAD         = 590
_DSS_OVERHEAD         = 690
_PRE_RANGE_OVERHEAD   = 660
_FINAL_RANGE_OVERHEAD = 550

# ST DefaultTuningSettings — opaque, written verbatim in this order.
_TUNING = (
    0xFF, 0x01, 0x00, 0x00, 0xFF, 0x00, 0x09, 0x00, 0x10, 0x00, 0x11, 0x00, 0x24, 0x01, 0x25, 0xFF, 0x75, 0x00,
    0xFF, 0x01, 0x4E, 0x2C, 0x48, 0x00, 0x30, 0x20, 0xFF, 0x00, 0x30, 0x09, 0x54, 0x00, 0x31, 0x04, 0x32, 0x03,
    0x40, 0x83, 0x46, 0x25, 0x60, 0x00, 0x27, 0x00, 0x50, 0x06, 0x51, 0x00, 0x52, 0x96, 0x56, 0x08, 0x57, 0x30,
    0x61, 0x00, 0x62, 0x00, 0x64, 0x00, 0x65, 0x00, 0x66, 0xA0, 0xFF, 0x01, 0x22, 0x32, 0x47, 0x14, 0x49, 0xFF,
    0x4A, 0x00, 0xFF, 0x00, 0x7A, 0x0A, 0x7B, 0x00, 0x78, 0x21, 0xFF, 0x01, 0x23, 0x34, 0x42, 0x00, 0x44, 0xFF,
    0x45, 0x26, 0x46, 0x05, 0x40, 0x40, 0x0E, 0x06, 0x20, 0x1A, 0x43, 0x40, 0xFF, 0x00, 0x34, 0x03, 0x35, 0x44,
    0xFF, 0x01, 0x31, 0x04, 0x4B, 0x09, 0x4C, 0x05, 0x4D, 0x04, 0xFF, 0x00, 0x44, 0x00, 0x45, 0x20, 0x47, 0x08,
    0x48, 0x28, 0x67, 0x00, 0x70, 0x04, 0x71, 0x01, 0x72, 0xFE, 0x76, 0x00, 0x77, 0x00, 0xFF, 0x01, 0x0D, 0x01,
    0xFF, 0x00, 0x80, 0x01, 0x01, 0xF8, 0xFF, 0x01, 0x8E, 0x01, 0x00, 0x01, 0xFF, 0x00, 0x80, 0x00,
)

# Pre-range PRE_RANGE_CONFIG_VALID_PHASE_HIGH per VCSEL period (PCLKs).
_PRE_PHASE_HIGH = {12: 0x18, 14: 0x30, 16: 0x40, 18: 0x50}

# Final-range (VALID_PHASE_HIGH, VALID_PHASE_LOW, VCSEL_WIDTH, PHASECAL_CONFIG_TIMEOUT,
# page-1 PHASECAL_LIM) per VCSEL period (PCLKs).
_FINAL_PHASE = {
    8:  (0x10, 0x08, 0x02, 0x0C, 0x30),
    10: (0x28, 0x08, 0x03, 0x09, 0x20),
    12: (0x38, 0x08, 0x03, 0x08, 0x20),
    14: (0x48, 0x08, 0x03, 0x07, 0x20),
}

# (signal-rate limit MCPS, pre-range PCLKs, final-range PCLKs, timing budget µs).
_PROFILES = {
    PROFILE_DEFAULT:       (0.25, 14, 10, 33000),
    PROFILE_LONG_RANGE:    (0.10, 18, 14, 33000),
    PROFILE_HIGH_SPEED:    (0.25, 14, 10, 20000),
    PROFILE_HIGH_ACCURACY: (0.25, 14, 10, 200000),
}


def _decode_vcsel(reg):
    return (reg + 1) << 1


def _encode_vcsel(pclks):
    return (pclks >> 1) - 1


def _macro_period_ns(pclks):
    return (2304 * pclks * 1655 + 500) // 1000


def _mclks_to_us(mclks, pclks):
    return (mclks * _macro_period_ns(pclks) + 500) // 1000


def _us_to_mclks(us, pclks):
    period = _macro_period_ns(pclks)
    return (us * 1000 + period // 2) // period


def _decode_timeout(reg16):
    return ((reg16 & 0xFF) << (reg16 >> 8)) + 1


def _encode_timeout(mclks):
    if mclks <= 0:
        return 0
    ls = mclks - 1
    ms = 0
    while ls > 0xFF:
        ls >>= 1
        ms += 1
    return (ms << 8) | (ls & 0xFF)


class VL53L0XMinimal(_VL53Base):
    """VL53L0X Time-of-Flight ranging sensor — minimal interface.

    Runs the full initialization sequence at construction (boot wait, model
    ID check, 2V8 I/O mode, reference SPADs, default tuning, GPIO1 = new
    sample ready active low, ~33 ms timing budget, VHV + phase reference
    calibration) and leaves the chip idle. distance() then takes one
    single-shot measurement per call.

    Args:
        connection: Configured I2C connection pointing at the device.

    Raises:
        ValueError: If IDENTIFICATION_MODEL_ID is not 0xEE.
        OSError: If a poll loop times out (500 ms).
    """

    def __init__(self, connection):
        super().__init__(connection, 1, 'VL53L0X')
        self._stop_variable = 0
        self._range_status = 0
        self._timing_budget_us = 0
        self._init()

    # --- Register access (8-bit index, via the family base) ---------------

    def _wr(self, reg, value):
        self._wr8(reg, value)

    def _rd(self, reg):
        return self._rd8(reg)

    def _wait(self, reg, mask, until_set, what):
        self._wait_until(lambda: bool(self._rd(reg) & mask) == until_set, what)

    # --- Initialization ---------------------------------------------------

    def _init(self):
        self._boot_wait()

        model = self._rd(_REG_MODEL_ID)
        if model != MODEL_ID:
            raise ValueError('VL53L0X not found: expected model ID 0x{:02X}, got 0x{:02X}'.format(
                MODEL_ID, model))

        # 2V8 I/O mode, standard I2C mode.
        self._wr(_REG_VHV_PAD_EXTSUP_HV, self._rd(_REG_VHV_PAD_EXTSUP_HV) | 0x01)
        self._wr(_REG_I2C_MODE, 0x00)

        # Stop variable.
        self._wr(_REG_POWER_FORCE, 0x01)
        self._wr(_REG_PAGE_SELECT, 0x01)
        self._wr(_REG_SYSRANGE_START, 0x00)
        self._stop_variable = self._rd(_REG_STOP_VARIABLE)
        self._wr(_REG_SYSRANGE_START, 0x01)
        self._wr(_REG_PAGE_SELECT, 0x00)
        self._wr(_REG_POWER_FORCE, 0x00)

        # Disable MSRC and pre-range signal-rate limit checks; 0.25 MCPS limit.
        self._wr(_REG_MSRC_CONFIG_CONTROL, self._rd(_REG_MSRC_CONFIG_CONTROL) | 0x12)
        self._wr16(_REG_FINAL_MIN_COUNT_RATE_RTN, 0x0020)
        self._wr(_REG_SYSTEM_SEQUENCE_CONFIG, 0xFF)

        spad_count, spad_is_aperture = self._spad_info()

        # Reference SPADs.
        ref_map = bytearray(self._rd_block(_REG_SPAD_ENABLES_REF_0, 6))
        self._wr(_REG_PAGE_SELECT, 0x01)
        self._wr(_REG_DYNAMIC_SPAD_START_OFFSET, 0x00)
        self._wr(_REG_DYNAMIC_SPAD_NUM_REQ, 0x2C)
        self._wr(_REG_PAGE_SELECT, 0x00)
        self._wr(_REG_REF_EN_START_SELECT, 0xB4)
        first = 12 if spad_is_aperture else 0
        enabled = 0
        for i in range(48):
            if i < first or enabled == spad_count:
                ref_map[i // 8] &= ~(1 << (i % 8)) & 0xFF
            elif (ref_map[i // 8] >> (i % 8)) & 0x01:
                enabled += 1
        self._wr_block(_REG_SPAD_ENABLES_REF_0, ref_map)

        # Default tuning settings.
        for i in range(0, len(_TUNING), 2):
            self._wr(_TUNING[i], _TUNING[i + 1])

        # GPIO1 = new sample ready, active low.
        self._wr(_REG_SYSTEM_INTERRUPT_CONFIG, SOURCE_NEW_SAMPLE_READY)
        self._wr(_REG_GPIO_HV_MUX_ACTIVE_HIGH, self._rd(_REG_GPIO_HV_MUX_ACTIVE_HIGH) & ~0x10)
        self._wr(_REG_SYSTEM_INTERRUPT_CLEAR, 0x01)

        budget = self._get_timing_budget()
        self._wr(_REG_SYSTEM_SEQUENCE_CONFIG, _SEQ_OPERATING)
        self._set_timing_budget(budget)

        # Reference calibration: VHV, then phase.
        self._wr(_REG_SYSTEM_SEQUENCE_CONFIG, 0x01)
        self._single_ref_calibration(0x40)
        self._wr(_REG_SYSTEM_SEQUENCE_CONFIG, 0x02)
        self._single_ref_calibration(0x00)
        self._wr(_REG_SYSTEM_SEQUENCE_CONFIG, _SEQ_OPERATING)

    def _spad_info(self):
        self._wr(_REG_POWER_FORCE, 0x01)
        self._wr(_REG_PAGE_SELECT, 0x01)
        self._wr(_REG_SYSRANGE_START, 0x00)
        self._wr(_REG_PAGE_SELECT, 0x06)
        self._wr(0x83, self._rd(0x83) | 0x04)
        self._wr(_REG_PAGE_SELECT, 0x07)
        self._wr(0x81, 0x01)
        self._wr(_REG_POWER_FORCE, 0x01)
        self._wr(0x94, 0x6B)
        self._wr(0x83, 0x00)
        self._wait(0x83, 0xFF, True, 'SPAD info')
        self._wr(0x83, 0x01)
        tmp = self._rd(0x92)
        self._wr(0x81, 0x00)
        self._wr(_REG_PAGE_SELECT, 0x06)
        self._wr(0x83, self._rd(0x83) & ~0x04)
        self._wr(_REG_PAGE_SELECT, 0x01)
        self._wr(_REG_SYSRANGE_START, 0x01)
        self._wr(_REG_PAGE_SELECT, 0x00)
        self._wr(_REG_POWER_FORCE, 0x00)
        return tmp & 0x7F, (tmp >> 7) & 0x01

    def _single_ref_calibration(self, vhv_init):
        self._wr(_REG_SYSRANGE_START, 0x01 | vhv_init)
        self._wait(_REG_RESULT_INTERRUPT_STATUS, 0x07, True, 'reference calibration')
        self._wr(_REG_SYSTEM_INTERRUPT_CLEAR, 0x01)
        self._wr(_REG_SYSRANGE_START, 0x00)

    # --- Timing budget ----------------------------------------------------

    def _step_timeouts(self, enables):
        pre_pclks = _decode_vcsel(self._rd(_REG_PRE_RANGE_VCSEL_PERIOD))
        msrc_us = _mclks_to_us(self._rd(_REG_MSRC_CONFIG_TIMEOUT) + 1, pre_pclks)
        pre_mclks = _decode_timeout(self._rd16(_REG_PRE_RANGE_TIMEOUT))
        pre_us = _mclks_to_us(pre_mclks, pre_pclks)
        final_pclks = _decode_vcsel(self._rd(_REG_FINAL_RANGE_VCSEL_PERIOD))
        final_mclks = _decode_timeout(self._rd16(_REG_FINAL_RANGE_TIMEOUT))
        if enables & _SEQ_PRE_RANGE:
            final_mclks -= pre_mclks
        final_us = _mclks_to_us(final_mclks, final_pclks)
        return pre_pclks, msrc_us, pre_mclks, pre_us, final_pclks, final_us

    def _fixed_overhead_us(self, enables, msrc_us, pre_us):
        budget = _START_OVERHEAD + _END_OVERHEAD
        if enables & _SEQ_TCC:
            budget += msrc_us + _TCC_OVERHEAD
        if enables & _SEQ_DSS:
            budget += 2 * (msrc_us + _DSS_OVERHEAD)
        elif enables & _SEQ_MSRC:
            budget += msrc_us + _MSRC_OVERHEAD
        if enables & _SEQ_PRE_RANGE:
            budget += pre_us + _PRE_RANGE_OVERHEAD
        return budget

    def _get_timing_budget(self):
        enables = self._rd(_REG_SYSTEM_SEQUENCE_CONFIG)
        _, msrc_us, _, pre_us, _, final_us = self._step_timeouts(enables)
        budget = self._fixed_overhead_us(enables, msrc_us, pre_us)
        if enables & _SEQ_FINAL_RANGE:
            budget += final_us + _FINAL_RANGE_OVERHEAD
        return budget

    def _set_timing_budget(self, budget_us):
        if budget_us < _MIN_TIMING_BUDGET_US:
            raise ValueError('timing budget must be >= {} us'.format(_MIN_TIMING_BUDGET_US))
        enables = self._rd(_REG_SYSTEM_SEQUENCE_CONFIG)
        _, msrc_us, pre_mclks, pre_us, final_pclks, _ = self._step_timeouts(enables)
        used = self._fixed_overhead_us(enables, msrc_us, pre_us)
        if enables & _SEQ_FINAL_RANGE:
            used += _FINAL_RANGE_OVERHEAD
            if used > budget_us:
                raise ValueError('timing budget {} us is below the enabled steps overhead {} us'.format(
                    budget_us, used))
            final_mclks = _us_to_mclks(budget_us - used, final_pclks)
            if enables & _SEQ_PRE_RANGE:
                final_mclks += pre_mclks
            self._wr16(_REG_FINAL_RANGE_TIMEOUT, _encode_timeout(final_mclks))
        self._timing_budget_us = budget_us

    # --- Ranging ----------------------------------------------------------

    def _stop_variable_preamble(self):
        self._wr(_REG_POWER_FORCE, 0x01)
        self._wr(_REG_PAGE_SELECT, 0x01)
        self._wr(_REG_SYSRANGE_START, 0x00)
        self._wr(_REG_STOP_VARIABLE, self._stop_variable)
        self._wr(_REG_SYSRANGE_START, 0x01)
        self._wr(_REG_PAGE_SELECT, 0x00)
        self._wr(_REG_POWER_FORCE, 0x00)

    def _read_result(self):
        data = self._rd_block(_REG_RESULT_RANGE_STATUS, 12)
        self._wr(_REG_SYSTEM_INTERRUPT_CLEAR, 0x01)
        self._range_status = (data[0] & 0x78) >> 3
        return data

    def _wait_and_read(self):
        self._wait(_REG_RESULT_INTERRUPT_STATUS, 0x07, True, 'data ready')
        data = self._read_result()
        return (data[10] << 8) | data[11]

    def distance(self):
        """Take one single-shot measurement.

        Blocks for about one timing budget (33 ms by default). Returns the
        raw range even when the measurement is not valid — typically 8190 or
        8191 with no target in range; check range_valid().

        Returns:
            int: Distance in mm.

        Raises:
            OSError: If the measurement does not start or complete within 500 ms.
        """
        with self._lock:
            self._stop_variable_preamble()
            self._wr(_REG_SYSRANGE_START, 0x01)
            self._wait(_REG_SYSRANGE_START, 0x01, False, 'ranging start')
            return self._wait_and_read()

    def range_valid(self):
        """Report whether the most recent measurement was valid.

        Returns:
            bool: True iff the device range status was 11 (range complete).
        """
        return self._range_status == RANGE_STATUS_VALID

    def _poll_interrupt_status(self):
        with self._lock:
            status = self._rd(_REG_RESULT_INTERRUPT_STATUS) & 0x07
            if status:
                self._wr(_REG_SYSTEM_INTERRUPT_CLEAR, 0x01)
            return status


class VL53L0XFull(VL53L0XMinimal):
    """VL53L0X full interface — extends Minimal with continuous and timed
    ranging, the full measurement record, timing budget, signal-rate limit,
    VCSEL pulse periods, ranging profiles, offset and crosstalk
    compensation, reference recalibration, address change, distance
    thresholds, identification, and the Level-2 interrupt API.

    Args:
        connection: Configured I2C connection pointing at the device.

    Raises:
        ValueError: If IDENTIFICATION_MODEL_ID is not 0xEE.
        OSError: If a poll loop times out (500 ms).
    """

    # --- Continuous ranging -----------------------------------------------

    def start_continuous(self, period_ms=0):
        """Start continuous ranging.

        Args:
            period_ms: 0 for back-to-back mode; otherwise timed mode with
                this inter-measurement period in ms (should be >= the timing
                budget).
        """
        with self._lock:
            self._stop_variable_preamble()
            if period_ms > 0:
                osc = self._rd16(_REG_OSC_CALIBRATE_VAL)
                if osc != 0:
                    period_ms *= osc
                self._wr32(_REG_SYSTEM_INTERMEASUREMENT, period_ms)
                self._wr(_REG_SYSRANGE_START, 0x04)
            else:
                self._wr(_REG_SYSRANGE_START, 0x02)

    def stop_continuous(self):
        """Stop continuous ranging. Does not wait for a running measurement."""
        with self._lock:
            self._wr(_REG_SYSRANGE_START, 0x01)
            self._wr(_REG_PAGE_SELECT, 0x01)
            self._wr(_REG_SYSRANGE_START, 0x00)
            self._wr(_REG_STOP_VARIABLE, 0x00)
            self._wr(_REG_SYSRANGE_START, 0x01)
            self._wr(_REG_PAGE_SELECT, 0x00)

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
            bool: True if RESULT_INTERRUPT_STATUS bits 2:0 are non-zero.
        """
        return (self._rd(_REG_RESULT_INTERRUPT_STATUS) & 0x07) != 0

    def read_measurement(self):
        """Read the full result block and clear the interrupt (non-blocking).

        Returns:
            dict: distance_mm (int), range_status (int), signal_rate_mcps
            (float), ambient_rate_mcps (float), effective_spad_count (float).
        """
        with self._lock:
            data = self._read_result()
            return {
                'distance_mm': (data[10] << 8) | data[11],
                'range_status': self._range_status,
                'signal_rate_mcps': ((data[6] << 8) | data[7]) / 128,
                'ambient_rate_mcps': ((data[8] << 8) | data[9]) / 128,
                'effective_spad_count': ((data[2] << 8) | data[3]) / 256,
            }

    def range_status(self):
        """Device range status of the most recent measurement.

        Returns:
            int: 0-15; 11 = valid, 4 = no target (MSRC), see the spec table.
        """
        return self._range_status

    # --- Timing and signal ------------------------------------------------

    def set_timing_budget(self, budget_us):
        """Set the per-measurement timing budget.

        Args:
            budget_us: Budget in µs, >= 20000.

        Raises:
            ValueError: If below 20000 µs or below the enabled steps' overhead.
        """
        with self._lock:
            self._set_timing_budget(budget_us)

    def timing_budget(self):
        """Compute the timing budget from the current registers.

        Returns:
            int: Budget in µs.
        """
        with self._lock:
            return self._get_timing_budget()

    def set_signal_rate_limit(self, limit_mcps):
        """Set the final-range return signal-rate limit.

        Lower values extend range but admit noisier readings.

        Args:
            limit_mcps: Limit in MCPS, 0 to 511.99.

        Raises:
            ValueError: If out of range.
        """
        if limit_mcps < 0 or limit_mcps > 511.99:
            raise ValueError('signal rate limit must be 0 to 511.99 MCPS')
        self._wr16(_REG_FINAL_MIN_COUNT_RATE_RTN, int(limit_mcps * 128 + 0.5))

    def signal_rate_limit(self):
        """Read the final-range return signal-rate limit.

        Returns:
            float: Limit in MCPS.
        """
        return self._rd16(_REG_FINAL_MIN_COUNT_RATE_RTN) / 128

    def set_vcsel_pulse_period(self, period_type, pclks):
        """Set a VCSEL pulse period, then re-apply the timing budget and redo
        the phase reference calibration.

        Args:
            period_type: PRE_RANGE ('pre_range') or FINAL_RANGE ('final_range').
            pclks: Pre-range 12, 14, 16 or 18; final-range 8, 10, 12 or 14.

        Raises:
            ValueError: If period_type or pclks is invalid.
            OSError: If the phase calibration times out.
        """
        with self._lock:
            if period_type == PRE_RANGE:
                if pclks not in _PRE_PHASE_HIGH:
                    raise ValueError('pre-range VCSEL period must be 12, 14, 16 or 18')
            elif period_type == FINAL_RANGE:
                if pclks not in _FINAL_PHASE:
                    raise ValueError('final-range VCSEL period must be 8, 10, 12 or 14')
            else:
                raise ValueError("period_type must be 'pre_range' or 'final_range'")

            enables = self._rd(_REG_SYSTEM_SEQUENCE_CONFIG)
            _, msrc_us, pre_mclks, pre_us, _, final_us = self._step_timeouts(enables)
            vcsel = _encode_vcsel(pclks)

            if period_type == PRE_RANGE:
                self._wr(_REG_PRE_VALID_PHASE_HIGH, _PRE_PHASE_HIGH[pclks])
                self._wr(_REG_PRE_VALID_PHASE_LOW, 0x08)
                self._wr(_REG_PRE_RANGE_VCSEL_PERIOD, vcsel)
                self._wr16(_REG_PRE_RANGE_TIMEOUT, _encode_timeout(_us_to_mclks(pre_us, pclks)))
                m = _us_to_mclks(msrc_us, pclks)
                self._wr(_REG_MSRC_CONFIG_TIMEOUT, 255 if m > 256 else m - 1)
            else:
                high, low, width, phasecal, lim = _FINAL_PHASE[pclks]
                self._wr(_REG_FINAL_VALID_PHASE_HIGH, high)
                self._wr(_REG_FINAL_VALID_PHASE_LOW, low)
                self._wr(_REG_GLOBAL_CONFIG_VCSEL_WIDTH, width)
                self._wr(_REG_PHASECAL_CONFIG_TIMEOUT, phasecal)
                self._wr(_REG_PAGE_SELECT, 0x01)
                self._wr(_REG_PHASECAL_CONFIG_TIMEOUT, lim)
                self._wr(_REG_PAGE_SELECT, 0x00)
                self._wr(_REG_FINAL_RANGE_VCSEL_PERIOD, vcsel)
                f = _us_to_mclks(final_us, pclks)
                if enables & _SEQ_PRE_RANGE:
                    f += pre_mclks
                self._wr16(_REG_FINAL_RANGE_TIMEOUT, _encode_timeout(f))

            self._set_timing_budget(self._timing_budget_us)
            seq = self._rd(_REG_SYSTEM_SEQUENCE_CONFIG)
            self._wr(_REG_SYSTEM_SEQUENCE_CONFIG, 0x02)
            self._single_ref_calibration(0x00)
            self._wr(_REG_SYSTEM_SEQUENCE_CONFIG, seq)

    def vcsel_pulse_period(self, period_type):
        """Read a VCSEL pulse period.

        Args:
            period_type: PRE_RANGE ('pre_range') or FINAL_RANGE ('final_range').

        Returns:
            int: Period in PCLKs.

        Raises:
            ValueError: If period_type is invalid.
        """
        if period_type == PRE_RANGE:
            return _decode_vcsel(self._rd(_REG_PRE_RANGE_VCSEL_PERIOD))
        if period_type == FINAL_RANGE:
            return _decode_vcsel(self._rd(_REG_FINAL_RANGE_VCSEL_PERIOD))
        raise ValueError("period_type must be 'pre_range' or 'final_range'")

    def set_profile(self, profile):
        """Apply a ranging profile: signal-rate limit, VCSEL periods (pre
        first), then timing budget.

        | Profile         | Limit     | Pre / final | Budget    |
        |-----------------|-----------|-------------|-----------|
        | 'default'       | 0.25 MCPS | 14 / 10     | 33 000 µs |
        | 'long_range'    | 0.10 MCPS | 18 / 14     | 33 000 µs |
        | 'high_speed'    | 0.25 MCPS | 14 / 10     | 20 000 µs |
        | 'high_accuracy' | 0.25 MCPS | 14 / 10     | 200 000 µs |

        Args:
            profile: One of the PROFILE_* constants.

        Raises:
            ValueError: If profile is unknown.
        """
        with self._lock:
            if profile not in _PROFILES:
                raise ValueError("profile must be 'default', 'long_range', 'high_speed' or 'high_accuracy'")
            limit, pre, final, budget = _PROFILES[profile]
            self.set_signal_rate_limit(limit)
            self.set_vcsel_pulse_period(PRE_RANGE, pre)
            self.set_vcsel_pulse_period(FINAL_RANGE, final)
            self._set_timing_budget(budget)

        # --- Offset and crosstalk ---------------------------------------------

    def set_offset(self, offset_mm):
        """Override the part-to-part range offset (volatile).

        Args:
            offset_mm: Offset in mm, -512.0 to 511.75 (0.25 mm steps).

        Raises:
            ValueError: If out of range.
        """
        if offset_mm < -512.0 or offset_mm > 511.75:
            raise ValueError('offset must be -512.0 to 511.75 mm')
        q = offset_mm * 4
        q = int(q + 0.5) if q >= 0 else -int(-q + 0.5)
        self._wr16(_REG_PART_TO_PART_RANGE_OFFSET, q & 0x0FFF)

    def offset(self):
        """Read the part-to-part range offset.

        Returns:
            float: Offset in mm.
        """
        raw = self._rd16(_REG_PART_TO_PART_RANGE_OFFSET) & 0x0FFF
        if raw & 0x0800:
            raw -= 0x1000
        return raw * 0.25

    def set_crosstalk_compensation(self, rate_mcps):
        """Set the crosstalk compensation peak rate (volatile).

        Args:
            rate_mcps: 0 disables compensation; otherwise 0 < rate < 8.0 MCPS,
                from the host's own cover-glass calibration.

        Raises:
            ValueError: If out of range.
        """
        if rate_mcps < 0 or rate_mcps >= 8.0:
            raise ValueError('crosstalk rate must be 0 (off) or below 8.0 MCPS')
        self._wr16(_REG_CROSSTALK_COMPENSATION, int(rate_mcps * 8192 + 0.5))

    def recalibrate(self):
        """Re-run the VHV and phase reference calibrations.

        Call in software standby (not while continuous ranging), and after
        the die temperature changes by more than 8 °C.

        Raises:
            OSError: If a calibration times out.
        """
        with self._lock:
            seq = self._rd(_REG_SYSTEM_SEQUENCE_CONFIG)
            self._wr(_REG_SYSTEM_SEQUENCE_CONFIG, 0x01)
            self._single_ref_calibration(0x40)
            self._wr(_REG_SYSTEM_SEQUENCE_CONFIG, 0x02)
            self._single_ref_calibration(0x00)
            self._wr(_REG_SYSTEM_SEQUENCE_CONFIG, seq)

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
            low_mm: Low threshold in mm (2 mm resolution).
            high_mm: High threshold in mm, low_mm <= high_mm <= 8190.

        Raises:
            ValueError: If out of range.
        """
        if low_mm < 0 or high_mm < low_mm or high_mm > 8190:
            raise ValueError('thresholds must satisfy 0 <= low <= high <= 8190 mm')
        self._wr16(_REG_SYSTEM_THRESH_LOW, (low_mm // 2) & 0x0FFF)
        self._wr16(_REG_SYSTEM_THRESH_HIGH, (high_mm // 2) & 0x0FFF)

    def interrupt_thresholds(self):
        """Read the distance thresholds.

        Returns:
            tuple: (low_mm: int, high_mm: int).
        """
        return ((self._rd16(_REG_SYSTEM_THRESH_LOW) & 0x0FFF) * 2,
                (self._rd16(_REG_SYSTEM_THRESH_HIGH) & 0x0FFF) * 2)

    def model_id(self):
        """Read IDENTIFICATION_MODEL_ID.

        Returns:
            int: 0xEE.
        """
        return self._rd(_REG_MODEL_ID)

    def revision_id(self):
        """Read IDENTIFICATION_REVISION_ID.

        Returns:
            int: Revision ID (0x10 on current silicon).
        """
        return self._rd(_REG_REVISION_ID)

    # --- Interrupt API ----------------------------------------------------

    def enable_interrupt(self, source):
        """Select the GPIO1 interrupt source (replaces the active one).

        With a threshold source active, data_ready()/read_continuous() only
        see a pending status when the threshold condition is met.

        Args:
            source: One of the SOURCE_* constants.

        Raises:
            ValueError: If source is not 1-4.
        """
        if source < SOURCE_LEVEL_LOW or source > SOURCE_NEW_SAMPLE_READY:
            raise ValueError('source must be one of the SOURCE_* constants')
        self._wr(_REG_SYSTEM_INTERRUPT_CONFIG, source)

    def disable_interrupt(self, source):
        """Disable GPIO1 interrupts if source is the active one.

        Args:
            source: One of the SOURCE_* constants.
        """
        if (self._rd(_REG_SYSTEM_INTERRUPT_CONFIG) & 0x07) == source:
            self._wr(_REG_SYSTEM_INTERRUPT_CONFIG, 0x00)

    def poll_interrupt(self):
        """Read and clear the pending interrupt status.

        Returns:
            int: The SOURCE_* value that fired, or 0 if nothing is pending.
        """
        return self._poll_interrupt_status()

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to GPIO1 interrupt events.

        Delivery: if int_pin is given, it is wired directly. Otherwise falls
        back to connection.int_pin (falling edge, GPIO1 is active low), or a
        5 ms polling thread on Linux if neither is available. The driver
        reads and clears the status before invoking the callback; the
        polling fallback consumes results, so don't mix it with
        read_continuous().

        Args:
            callback: Callable(status: int) — the SOURCE_* value that fired.
            int_pin: Optional InputPin for this call, overriding connection.int_pin.
        """
        self._subscribe(callback, int_pin)

    def off_interrupt(self):
        """Unsubscribe and stop delivery."""
        self._unsubscribe()
