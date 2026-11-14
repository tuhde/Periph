"""VL6180X — proximity and ambient-light sensor (STMicroelectronics).

VCSEL emitter, SPAD time-of-flight receiver and ambient light sensor (ALS)
behind one aperture. Measures absolute distance 0-100 mm (8-bit result in mm)
and ambient light (16-bit count, converted to lux). Registers use a 16-bit
big-endian index; 16-bit results and thresholds are MSB first. The same
driver file is used on MicroPython, CircuitPython, and Linux hosts.

The connection must be created with ``reg_bytes=2`` (16-bit register
addresses) and point at address 0x29. The mandatory private-register
settings applied at construction come from ST application note AN4545 and
are not documented in the datasheet.

Args:
    connection: Configured I2C RegisterConnection (reg_bytes=2) at 0x29. Its
        optional int_pin receives GPIO1, its optional en_pin drives GPIO0/CE.
"""

import time

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False


I2C_ADDRESS = 0x29
MODEL_ID = 0xB4

# Interrupt sources returned by poll_interrupt() (bit mask) and accepted by
# enable_interrupt() / disable_interrupt().
SOURCE_RANGE = 0x01
SOURCE_ALS = 0x02
SOURCE_ERROR = 0x04

# Interrupt modes for enable_interrupt().
MODE_LEVEL_LOW = 1
MODE_LEVEL_HIGH = 2
MODE_OUT_OF_WINDOW = 3
MODE_NEW_SAMPLE_READY = 4

_REG_MODEL_ID = 0x000
_REG_MODEL_REV_MAJOR = 0x001
_REG_MODEL_REV_MINOR = 0x002
_REG_MODE_GPIO1 = 0x011
_REG_HISTORY_CTRL = 0x012
_REG_INTERRUPT_CONFIG = 0x014
_REG_INTERRUPT_CLEAR = 0x015
_REG_FRESH_OUT_OF_RESET = 0x016
_REG_GROUPED_PARAMETER_HOLD = 0x017
_REG_RANGE_START = 0x018
_REG_RANGE_THRESH_HIGH = 0x019
_REG_RANGE_THRESH_LOW = 0x01A
_REG_RANGE_INTERMEASUREMENT = 0x01B
_REG_RANGE_MAX_CONVERGENCE = 0x01C
_REG_RANGE_CROSSTALK_RATE = 0x01E
_REG_RANGE_OFFSET = 0x024
_REG_VHV_RECALIBRATE = 0x02E
_REG_VHV_REPEAT_RATE = 0x031
_REG_ALS_START = 0x038
_REG_ALS_THRESH_HIGH = 0x03A
_REG_ALS_THRESH_LOW = 0x03C
_REG_ALS_INTERMEASUREMENT = 0x03E
_REG_ALS_GAIN = 0x03F
_REG_ALS_INTEGRATION = 0x040
_REG_RANGE_STATUS = 0x04D
_REG_INTERRUPT_STATUS = 0x04F
_REG_ALS_VAL = 0x050
_REG_HISTORY_BUFFER = 0x052
_REG_RANGE_VAL = 0x062
_REG_AVERAGING_PERIOD = 0x10A
_REG_RESULT_SCALER = 0x120
_REG_I2C_ADDRESS = 0x212
_REG_INTERLEAVED_ENABLE = 0x2A3

# Mandatory private-register settings (ST AN4545), applied while
# SYSTEM__FRESH_OUT_OF_RESET = 1.
_PRIVATE_SETTINGS = (
    (0x0207, 0x01), (0x0208, 0x01), (0x0096, 0x00), (0x0097, 0xFD),
    (0x00E3, 0x01), (0x00E4, 0x03), (0x00E5, 0x02), (0x00E6, 0x01),
    (0x00E7, 0x03), (0x00F5, 0x02), (0x00D9, 0x05), (0x00DB, 0xCE),
    (0x00DC, 0x03), (0x00DD, 0xF8), (0x009F, 0x00), (0x00A3, 0x3C),
    (0x00B7, 0x00), (0x00BB, 0x3C), (0x00B2, 0x09), (0x00CA, 0x09),
    (0x0198, 0x01), (0x01B0, 0x17), (0x01AD, 0x00), (0x00FF, 0x05),
    (0x0100, 0x05), (0x0199, 0x05), (0x01A6, 0x1B), (0x01AC, 0x3E),
    (0x01A7, 0x1F), (0x0030, 0x00),
)

# Recommended public settings (datasheet / AN4545).
_RECOMMENDED_SETTINGS = (
    (_REG_MODE_GPIO1, 0x10), (_REG_AVERAGING_PERIOD, 0x30), (_REG_ALS_GAIN, 0x46),
    (_REG_VHV_REPEAT_RATE, 0xFF), (_REG_VHV_RECALIBRATE, 0x01),
    (_REG_RANGE_INTERMEASUREMENT, 0x09), (_REG_ALS_INTERMEASUREMENT, 0x31),
    (_REG_INTERRUPT_CONFIG, 0x24),
)

# Actual ALS gain per gain code 0-7.
_GAIN = (20.0, 10.32, 5.21, 2.60, 1.72, 1.28, 1.01, 40.0)

_RANGE_TIMEOUT_MS = 100
_VHV_TIMEOUT_MS = 100
_BOOT_S = 0.001


def _ticks_ms():
    if hasattr(time, 'ticks_ms'):
        return time.ticks_ms()
    if hasattr(time, 'monotonic'):
        return int(time.monotonic() * 1000)
    return int(time.time() * 1000)


def _elapsed_ms(start):
    if hasattr(time, 'ticks_diff'):
        return time.ticks_diff(time.ticks_ms(), start)
    return _ticks_ms() - start


class _NoLock:
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class VL6180XMinimal:
    """VL6180X proximity sensor — minimal interface.

    Runs the initialization sequence at construction: 1 ms boot wait, then —
    only while SYSTEM__FRESH_OUT_OF_RESET is 1 — the mandatory private
    settings, then the recommended settings (averaging period 48, max
    convergence 49 ms, ALS gain 1, 100 ms ALS integration, new-sample
    interrupt configuration). read_range() then takes one single-shot
    measurement per call.

    Args:
        connection: Configured I2C RegisterConnection (reg_bytes=2) at 0x29.

    Raises:
        OSError: If the bus transfer fails.
    """

    def __init__(self, connection):
        self._conn = connection
        self._lock = _threading.RLock() if _LINUX else _NoLock()
        self._callback = None
        self._int_pin_used = None
        self._poll_stop = False
        self._poll_thread = None
        self._gain_code = 6
        self._integration_ms = 100
        self._init()

    # --- Register access --------------------------------------------------

    def _rd8(self, reg):
        return self._conn.read_reg(reg, 1)[0]

    def _wr8(self, reg, value):
        self._conn.write_reg(reg, bytes([value & 0xFF]))

    def _rd16(self, reg):
        data = self._conn.read_reg(reg, 2)
        return (data[0] << 8) | data[1]

    def _wr16(self, reg, value):
        self._conn.write_reg(reg, bytes([(value >> 8) & 0xFF, value & 0xFF]))

    def _wait_until(self, predicate, timeout_ms, what):
        start = _ticks_ms()
        while True:
            if predicate():
                return
            if _elapsed_ms(start) > timeout_ms:
                raise OSError('VL6180X timeout waiting for ' + what)

    # --- Initialization ---------------------------------------------------

    def _init(self):
        time.sleep(_BOOT_S)
        if self._rd8(_REG_FRESH_OUT_OF_RESET) == 1:
            for reg, value in _PRIVATE_SETTINGS:
                self._wr8(reg, value)
            self._wr8(_REG_FRESH_OUT_OF_RESET, 0)
        for reg, value in _RECOMMENDED_SETTINGS:
            self._wr8(reg, value)
        self._wr16(_REG_ALS_INTEGRATION, 0x0063)

    # --- Ranging ----------------------------------------------------------

    def _range_ready(self):
        return (self._rd8(_REG_INTERRUPT_STATUS) & 0x07) == 4

    def _measure_range(self):
        # Caller holds the lock. Returns (range_mm, error_code).
        self._wr8(_REG_RANGE_START, 0x01)
        try:
            self._wait_until(self._range_ready, _RANGE_TIMEOUT_MS, 'range result')
        except OSError:
            self._wr8(_REG_INTERRUPT_CLEAR, 0x07)
            raise
        return self._read_range_result()

    def _read_range_result(self):
        error = self._rd8(_REG_RANGE_STATUS) >> 4
        mm = self._rd8(_REG_RANGE_VAL)
        self._wr8(_REG_INTERRUPT_CLEAR, 0x07)
        return mm, error

    def read_range(self):
        """Take one single-shot range measurement.

        Returns:
            int: Distance in mm (0-255; reliable up to about 100 mm).

        Raises:
            RuntimeError: If the range error code is non-zero.
            OSError: If no result arrives within 100 ms.
        """
        with self._lock:
            mm, error = self._measure_range()
        if error != 0:
            raise RuntimeError('VL6180X range error {}'.format(error))
        return mm

    # --- Interrupt status (shared with Full) -------------------------------

    def _poll_interrupt_status(self):
        with self._lock:
            status = self._rd8(_REG_INTERRUPT_STATUS)
            sources = 0
            if status & 0x07:
                sources |= SOURCE_RANGE
            if status & 0x38:
                sources |= SOURCE_ALS
            if status & 0xC0:
                sources |= SOURCE_ERROR
            if sources:
                self._wr8(_REG_INTERRUPT_CLEAR, 0x07)
            return sources


class VL6180XFull(VL6180XMinimal):
    """VL6180X full interface — extends Minimal with ALS (lux), continuous
    and interleaved modes, thresholds, gain / integration / scaler,
    convergence and averaging, range offset and crosstalk, VHV
    recalibration, address change, identification, the history buffer and
    the Level-2 interrupt API.

    Args:
        connection: Configured I2C RegisterConnection (reg_bytes=2) at 0x29.

    Raises:
        OSError: If the bus transfer fails.
    """

    # --- Range ------------------------------------------------------------

    def read_range_status(self):
        """Take one single-shot range measurement without raising on errors.

        Returns:
            tuple: (range_mm, error_code); the range is valid only when the
            error code is 0 (1-15 are the datasheet range error codes).

        Raises:
            OSError: If no result arrives within 100 ms.
        """
        with self._lock:
            return self._measure_range()

    def start_continuous_range(self, period_ms):
        """Start continuous ranging.

        Args:
            period_ms: Period in ms, 10-2550 in 10 ms steps.

        Raises:
            ValueError: If period_ms is out of range.
        """
        if period_ms < 10 or period_ms > 2550:
            raise ValueError('period must be 10 to 2550 ms')
        with self._lock:
            self._wr8(_REG_RANGE_INTERMEASUREMENT, period_ms // 10 - 1)
            self._wr8(_REG_INTERRUPT_CLEAR, 0x07)
            self._wr8(_REG_RANGE_START, 0x03)

    def stop_continuous_range(self):
        """Stop continuous ranging (start/stop bit toggles the running mode)."""
        self._wr8(_REG_RANGE_START, 0x01)

    def read_range_continuous(self):
        """Wait for the next continuous-mode range sample and read it.

        Returns:
            int: Distance in mm.

        Raises:
            RuntimeError: If the range error code is non-zero.
            OSError: If no sample arrives within 100 ms plus one period.
        """
        with self._lock:
            period = (self._rd8(_REG_RANGE_INTERMEASUREMENT) + 1) * 10
            self._wait_until(self._range_ready, _RANGE_TIMEOUT_MS + period, 'range sample')
            mm, error = self._read_range_result()
        if error != 0:
            raise RuntimeError('VL6180X range error {}'.format(error))
        return mm

    # --- ALS --------------------------------------------------------------

    def _als_ready(self):
        return ((self._rd8(_REG_INTERRUPT_STATUS) >> 3) & 0x07) == 4

    def _read_als_result(self):
        raw = self._rd16(_REG_ALS_VAL)
        self._wr8(_REG_INTERRUPT_CLEAR, 0x07)
        return raw

    def read_als_raw(self):
        """Take one single-shot ALS measurement.

        Returns:
            int: 16-bit ALS count.

        Raises:
            OSError: If no result arrives within the integration time + 100 ms.
        """
        with self._lock:
            self._wr8(_REG_ALS_START, 0x01)
            self._wait_until(self._als_ready, self._integration_ms + 100, 'ALS result')
            return self._read_als_result()

    def read_lux(self):
        """Take one single-shot ALS measurement and convert it to lux.

        lux = 0.32 * (count / gain) * (100 / integration_ms), using the
        actual gain of the current gain code.

        Returns:
            float: Illuminance in lux (without cover glass).

        Raises:
            OSError: If no result arrives within the integration time + 100 ms.
        """
        raw = self.read_als_raw()
        return 0.32 * (raw / _GAIN[self._gain_code]) * (100.0 / self._integration_ms)

    def start_continuous_als(self, period_ms):
        """Start continuous ALS measurements.

        Args:
            period_ms: Period in ms, 10-2550 in 10 ms steps.

        Raises:
            ValueError: If period_ms is out of range.
        """
        if period_ms < 10 or period_ms > 2550:
            raise ValueError('period must be 10 to 2550 ms')
        with self._lock:
            self._wr8(_REG_ALS_INTERMEASUREMENT, period_ms // 10 - 1)
            self._wr8(_REG_INTERRUPT_CLEAR, 0x07)
            self._wr8(_REG_ALS_START, 0x03)

    def stop_continuous_als(self):
        """Stop continuous ALS measurements (start/stop bit toggles the running mode)."""
        self._wr8(_REG_ALS_START, 0x01)

    def set_interleaved(self, on):
        """Enable or disable interleaved ALS + range mode.

        In interleaved mode a single start of ALS (start_continuous_als)
        runs both measurements alternately.

        Args:
            on: True to enable.
        """
        self._wr8(_REG_INTERLEAVED_ENABLE, 1 if on else 0)

    def set_als_gain(self, code):
        """Set the ALS analogue gain.

        Args:
            code: Gain code 0-7 (0 = 20, 1 = 10, 2 = 5, 3 = 2.5, 4 = 1.67,
                5 = 1.25, 6 = 1, 7 = 40).

        Raises:
            ValueError: If code is out of range.
        """
        if code < 0 or code > 7:
            raise ValueError('gain code must be 0 to 7')
        with self._lock:
            self._wr8(_REG_GROUPED_PARAMETER_HOLD, 1)
            self._wr8(_REG_ALS_GAIN, 0x40 | code)
            self._wr8(_REG_GROUPED_PARAMETER_HOLD, 0)
            self._gain_code = code

    def set_als_integration_time(self, ms):
        """Set the ALS integration time.

        Args:
            ms: Integration time in ms, 1-512.

        Raises:
            ValueError: If ms is out of range.
        """
        if ms < 1 or ms > 512:
            raise ValueError('integration time must be 1 to 512 ms')
        with self._lock:
            self._wr8(_REG_GROUPED_PARAMETER_HOLD, 1)
            self._wr16(_REG_ALS_INTEGRATION, ms - 1)
            self._wr8(_REG_GROUPED_PARAMETER_HOLD, 0)
            self._integration_ms = ms

    def set_als_scaler(self, scaler):
        """Set the ALS result scaler (FIRMWARE__RESULT_SCALER).

        Args:
            scaler: Scaler 1-16.

        Raises:
            ValueError: If scaler is out of range.
        """
        if scaler < 1 or scaler > 16:
            raise ValueError('scaler must be 1 to 16')
        self._wr8(_REG_RESULT_SCALER, scaler)

    # --- Thresholds and tuning --------------------------------------------

    def set_range_thresholds(self, low, high):
        """Set the range interrupt thresholds (grouped-parameter-hold protocol).

        Args:
            low: Low threshold in mm, 0-255.
            high: High threshold in mm, low <= high <= 255.

        Raises:
            ValueError: If out of range.
        """
        if low < 0 or high > 255 or low > high:
            raise ValueError('thresholds must satisfy 0 <= low <= high <= 255')
        with self._lock:
            self._wr8(_REG_GROUPED_PARAMETER_HOLD, 1)
            self._wr8(_REG_RANGE_THRESH_HIGH, high)
            self._wr8(_REG_RANGE_THRESH_LOW, low)
            self._wr8(_REG_GROUPED_PARAMETER_HOLD, 0)

    def set_als_thresholds(self, low, high):
        """Set the ALS interrupt thresholds (grouped-parameter-hold protocol).

        Args:
            low: Low threshold, ALS counts.
            high: High threshold, low <= high <= 65535.

        Raises:
            ValueError: If out of range.
        """
        if low < 0 or high > 65535 or low > high:
            raise ValueError('thresholds must satisfy 0 <= low <= high <= 65535')
        with self._lock:
            self._wr8(_REG_GROUPED_PARAMETER_HOLD, 1)
            self._wr16(_REG_ALS_THRESH_HIGH, high)
            self._wr16(_REG_ALS_THRESH_LOW, low)
            self._wr8(_REG_GROUPED_PARAMETER_HOLD, 0)

    def set_max_convergence_time(self, ms):
        """Set the maximum ranging convergence time.

        Args:
            ms: Time in ms, 1-63.

        Raises:
            ValueError: If ms is out of range.
        """
        if ms < 1 or ms > 63:
            raise ValueError('convergence time must be 1 to 63 ms')
        self._wr8(_REG_RANGE_MAX_CONVERGENCE, ms)

    def set_averaging_period(self, units):
        """Set the ranging averaging sample period.

        Args:
            units: 0-255; about 64.5 us per unit (48 is the default).

        Raises:
            ValueError: If units is out of range.
        """
        if units < 0 or units > 255:
            raise ValueError('averaging period must be 0 to 255')
        self._wr8(_REG_AVERAGING_PERIOD, units)

    def set_range_offset(self, mm):
        """Set the part-to-part range offset.

        Args:
            mm: Offset in mm, -128 to 127 (two's complement on the chip).

        Raises:
            ValueError: If out of range.
        """
        if mm < -128 or mm > 127:
            raise ValueError('offset must be -128 to 127 mm')
        self._wr8(_REG_RANGE_OFFSET, mm & 0xFF)

    def get_range_offset(self):
        """Read the part-to-part range offset.

        Returns:
            int: Offset in mm (signed).
        """
        value = self._rd8(_REG_RANGE_OFFSET)
        return value - 256 if value & 0x80 else value

    def set_crosstalk_rate(self, rate):
        """Set the crosstalk compensation rate.

        Args:
            rate: Rate in Mcps (stored as 9.7 fixed point), 0 to about 511.

        Raises:
            ValueError: If out of range.
        """
        code = int(rate * 128 + 0.5)
        if rate < 0 or code > 0xFFFF:
            raise ValueError('crosstalk rate out of range')
        self._wr16(_REG_RANGE_CROSSTALK_RATE, code)

    def recalibrate_vhv(self):
        """Start a VHV recalibration and wait for it to finish.

        Raises:
            OSError: If the start bit does not clear within 100 ms.
        """
        with self._lock:
            self._wr8(_REG_VHV_RECALIBRATE, 0x01)
            self._wait_until(lambda: (self._rd8(_REG_VHV_RECALIBRATE) & 0x01) == 0,
                             _VHV_TIMEOUT_MS, 'VHV recalibration')

    # --- Address, identification, history ----------------------------------

    def set_address(self, address):
        """Change the chip's I2C address (volatile).

        The chip answers on the new address immediately; the Connection
        passed at construction still points at the old one. Construct a new
        Connection at the new address (and a new driver) to continue.

        Args:
            address: New 7-bit address, 0x08-0x77.

        Raises:
            ValueError: If out of range.
        """
        if address < 0x08 or address > 0x77:
            raise ValueError('address must be 0x08 to 0x77')
        self._wr8(_REG_I2C_ADDRESS, address & 0x7F)

    def model_id(self):
        """Read the model ID.

        Returns:
            int: 0xB4 for the VL6180X.
        """
        return self._rd8(_REG_MODEL_ID)

    def revision(self):
        """Read the model revision.

        Returns:
            tuple: (major, minor), bits 2:0 of each revision register.
        """
        return (self._rd8(_REG_MODEL_REV_MAJOR) & 0x07, self._rd8(_REG_MODEL_REV_MINOR) & 0x07)

    def read_history(self):
        """Read the eight-entry history buffer.

        Enable it first through the history control register; this method
        only reads RESULT__HISTORY_BUFFER_0..7.

        Returns:
            list: Eight 16-bit entries (range in mm or ALS counts, per the
            history mode).
        """
        data = self._conn.read_reg(_REG_HISTORY_BUFFER, 16)
        return [(data[2 * i] << 8) | data[2 * i + 1] for i in range(8)]

    # --- Interrupts ----------------------------------------------------------

    def enable_interrupt(self, source, mode):
        """Select the interrupt mode of the range or ALS source.

        Args:
            source: SOURCE_RANGE or SOURCE_ALS.
            mode: MODE_LEVEL_LOW, MODE_LEVEL_HIGH, MODE_OUT_OF_WINDOW or
                MODE_NEW_SAMPLE_READY.

        Raises:
            ValueError: If source or mode is invalid.
        """
        if mode < 1 or mode > 4:
            raise ValueError('mode must be 1 to 4')
        self._set_interrupt_mode(source, mode)

    def disable_interrupt(self, source):
        """Disable the interrupt of the range or ALS source (mode 0).

        Args:
            source: SOURCE_RANGE or SOURCE_ALS.

        Raises:
            ValueError: If source is invalid.
        """
        self._set_interrupt_mode(source, 0)

    def _set_interrupt_mode(self, source, mode):
        if source == SOURCE_RANGE:
            shift = 0
        elif source == SOURCE_ALS:
            shift = 3
        else:
            raise ValueError('source must be SOURCE_RANGE or SOURCE_ALS')
        with self._lock:
            value = self._rd8(_REG_INTERRUPT_CONFIG)
            value = (value & ~(0x07 << shift) & 0xFF) | (mode << shift)
            self._wr8(_REG_GROUPED_PARAMETER_HOLD, 1)
            self._wr8(_REG_INTERRUPT_CONFIG, value)
            self._wr8(_REG_GROUPED_PARAMETER_HOLD, 0)

    def poll_interrupt(self):
        """Read the interrupt status and clear all pending sources.

        Returns:
            int: Bit mask of SOURCE_RANGE, SOURCE_ALS, SOURCE_ERROR (0 if none).
        """
        return self._poll_interrupt_status()

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to GPIO1 interrupt events.

        Delivery: if int_pin is given, it is wired directly. Otherwise falls
        back to connection.int_pin (falling edge, GPIO1 is active low), or a
        5 ms polling thread on Linux if neither is available. Pending
        sources are cleared before the callback runs.

        Args:
            callback: Callable(status: int) — the SOURCE_* bit mask.
            int_pin: Optional InputPin for this call, overriding connection.int_pin.
        """
        self._callback = callback
        pin = int_pin if int_pin is not None else getattr(self._conn, 'int_pin', None)
        self._int_pin_used = pin
        if pin is not None:
            from periph.connection.input_pin import InputPin
            pin.on_edge(self._int_handler, InputPin.FALLING)
        elif _LINUX:
            self._poll_stop = False
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()

    def off_interrupt(self):
        """Unsubscribe and stop delivery."""
        if self._int_pin_used is not None:
            self._int_pin_used.off_edge(self._int_handler)
            self._int_pin_used = None
        elif _LINUX:
            self._poll_stop = True
        self._callback = None

    def _int_handler(self):
        status = self._poll_interrupt_status()
        if status and self._callback:
            self._callback(status)

    def _poll_loop(self):
        while not self._poll_stop:
            status = self._poll_interrupt_status()
            if status and self._callback:
                self._callback(status)
            time.sleep(0.005)
