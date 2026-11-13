"""MAX44009 — ultra-low-power ambient light sensor with ADC (Maxim / Analog Devices).

An on-chip photodiode with automatic ranging reports the illuminance directly
as a 12-bit floating-point value (4-bit exponent, 8-bit mantissa):
``lux = 2^exponent * mantissa * 0.045`` from 0.045 lux to 188 006.4 lux. The
chip has a programmable interrupt (upper / lower threshold window plus a
delay timer, open-drain INT pin), an automatic mode (current divider and
100–800 ms integration time chosen by the chip) and a manual mode (6.25–800 ms
integration, current divider 1 or 1/8). The I²C address is 0x4A (A0 = GND) or
0x4B (A0 = VCC). The same driver file is used on MicroPython, CircuitPython,
and Linux hosts.

The driver uses ``RegisterConnection`` (1-byte register address) with one
register per transfer; the lux registers are read as high, low, high and the
sequence is repeated once if the high byte changed in between (a new
conversion arrived), because a ``RegisterConnection`` read ends with a STOP.

Args:
    connection: RegisterConnection (I²C) pointing at the device (0x4A or 0x4B).
"""

import time

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False

I2C_ADDRESS = 0x4A
I2C_ADDRESS_A0_HIGH = 0x4B

_REG_INT_STATUS = 0x00
_REG_INT_ENABLE = 0x01
_REG_CONFIG = 0x02
_REG_LUX_HIGH = 0x03
_REG_LUX_LOW = 0x04
_REG_UPPER = 0x05
_REG_LOWER = 0x06
_REG_TIMER = 0x07

_CFG_CONT = 0x80
_CFG_MANUAL = 0x40
_CFG_CDR = 0x08
_CFG_TIM_MASK = 0x07

_LUX_PER_LSB = 0.045
_MAX_LUX = 188006.4          # E = 14, M = 255
_OVERRANGE_EXP = 15

# Integration time in ms for TIM = 0..7.
_INTEGRATION_MS = (800.0, 400.0, 200.0, 100.0, 50.0, 25.0, 12.5, 6.25)

# Wait after init so the first lux() returns a real measurement
# (one 800 ms cycle plus the 100 ms integration time); tests may set it to 0.
_INIT_WAIT_S = 0.9
_POLL_INTERVAL_S = 0.1

SOURCE_THRESHOLD = 0x01


def _lux_from(exponent, mantissa):
    if exponent >= _OVERRANGE_EXP:
        return _MAX_LUX
    return (1 << exponent) * mantissa * _LUX_PER_LSB


def _encode_threshold(lux):
    """Encode a lux value as a threshold register byte (exponent + 4 mantissa MSBs)."""
    if lux < 11.5:
        return min(15, int(lux / (16 * _LUX_PER_LSB) + 0.5))
    for exponent in range(1, 15):
        mantissa = lux / ((1 << exponent) * _LUX_PER_LSB)
        if mantissa < 256:
            break
    mantissa = max(128, min(255, int(mantissa)))
    return min(0xEF, (exponent << 4) | (mantissa >> 4))


def _decode_threshold(code, upper):
    exponent = code >> 4
    nibble = code & 0x0F
    low_bits = 0x0F if upper else 0x00
    return (1 << exponent) * ((nibble << 4) | low_bits) * _LUX_PER_LSB


class MAX44009Minimal:
    """MAX44009 ambient light sensor — minimal interface.

    Reads the illuminance in lux with the chip in its power-on configuration:
    automatic range and integration time, default (non-continuous) mode with
    one measurement per 800 ms, interrupt disabled, threshold window fully
    open.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    def __init__(self, connection):
        """Initialize MAX44009Minimal and run the initialization sequence.

        Writes the reset state (interrupt off, CONFIG 0x03, thresholds fully
        open, timer 0xFF), reads INT_STATUS once to clear a stale interrupt and
        waits 0.9 s so the first ``lux()`` returns a real measurement.

        Args:
            connection: RegisterConnection (I²C) pointing at the device.
        """
        self._connection = connection
        self._write_reg(_REG_INT_ENABLE, 0x00)
        self._write_reg(_REG_CONFIG, 0x03)
        self._write_reg(_REG_UPPER, 0xFF)
        self._write_reg(_REG_LOWER, 0x00)
        self._write_reg(_REG_TIMER, 0xFF)
        self._read_reg(_REG_INT_STATUS)
        self._last_exponent = 0
        time.sleep(_INIT_WAIT_S)

    def _read_reg(self, reg):
        return self._connection.read_reg(reg, 1)[0]

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, bytes([value & 0xFF]))

    def _read_lux_regs(self):
        """Read LUX_HIGH, LUX_LOW and LUX_HIGH again; repeat once if the high byte changed."""
        high = self._read_reg(_REG_LUX_HIGH)
        low = self._read_reg(_REG_LUX_LOW)
        high2 = self._read_reg(_REG_LUX_HIGH)
        if high2 != high:
            high = high2
            low = self._read_reg(_REG_LUX_LOW)
        exponent = high >> 4
        mantissa = ((high & 0x0F) << 4) | (low & 0x0F)
        self._last_exponent = exponent
        return exponent, mantissa

    def lux(self):
        """Read the ambient illuminance.

        Returns:
            float: Illuminance in lux (``2^E * M * 0.045``); on overrange
            (E = 15) the maximum 188006.4 lux.
        """
        exponent, mantissa = self._read_lux_regs()
        return _lux_from(exponent, mantissa)


class MAX44009Full(MAX44009Minimal):
    """MAX44009 full interface — extends Minimal with raw readings, measurement
    mode and range configuration, interrupt thresholds and delay, and interrupt
    delivery.

    Args:
        connection: RegisterConnection (I²C) pointing at the device.
    """

    def __init__(self, connection):
        MAX44009Minimal.__init__(self, connection)
        self._callback = None
        self._poll_thread = None
        self._poll_stop = True
        self._int_source = None

    def read_raw(self):
        """Read the lux registers as one consistent sample.

        Returns:
            tuple: ``(exponent, mantissa, overrange)`` — exponent 0–14, 8-bit
            mantissa, ``overrange`` True if the exponent field was 15.
        """
        exponent, mantissa = self._read_lux_regs()
        return exponent, mantissa, exponent >= _OVERRANGE_EXP

    def is_overrange(self):
        """Report whether the last reading was overrange (E = 15).

        Returns:
            bool: True if the last reading had E = 15.
        """
        return self._last_exponent >= _OVERRANGE_EXP

    def resolution(self):
        """Lux per LSB of the mantissa for the last reading.

        Returns:
            float: ``2^E * 0.045`` for the exponent of the last reading.
        """
        exponent = min(self._last_exponent, 14)
        return (1 << exponent) * _LUX_PER_LSB

    def _update_config(self, set_bits, clear_bits):
        cfg = self._read_reg(_REG_CONFIG)
        self._write_reg(_REG_CONFIG, (cfg & ~clear_bits) | set_bits)

    def set_continuous(self, continuous):
        """Select continuous or low-power measurement mode.

        Args:
            continuous: True for a new measurement as soon as one finishes,
                False for one measurement every 800 ms (lowest power).
        """
        self._update_config(_CFG_CONT if continuous else 0, _CFG_CONT)

    def set_automatic(self):
        """Select automatic mode (the chip chooses current divider and integration time)."""
        self._update_config(0, _CFG_MANUAL)

    def set_manual(self, integration_ms, divide_by_8=False):
        """Select manual mode with a fixed integration time and current divider.

        Args:
            integration_ms: Integration time in ms; rounded to the nearest of
                800, 400, 200, 100, 50, 25, 12.5, 6.25.
            divide_by_8: True to divide the photodiode current by 8 (high brightness).
        """
        best = 0
        best_err = abs(_INTEGRATION_MS[0] - integration_ms)
        for tim in range(1, 8):
            err = abs(_INTEGRATION_MS[tim] - integration_ms)
            if err < best_err:
                best = tim
                best_err = err
        cfg = self._read_reg(_REG_CONFIG)
        cfg = (cfg & _CFG_CONT) | _CFG_MANUAL | (_CFG_CDR if divide_by_8 else 0) | best
        self._write_reg(_REG_CONFIG, cfg)

    def get_config(self):
        """Read the CONFIG register.

        Returns:
            dict: ``{'continuous', 'manual', 'divide_by_8', 'integration_ms'}``;
            in automatic mode the last two reflect the chip's current choice.
        """
        cfg = self._read_reg(_REG_CONFIG)
        return {
            'continuous': bool(cfg & _CFG_CONT),
            'manual': bool(cfg & _CFG_MANUAL),
            'divide_by_8': bool(cfg & _CFG_CDR),
            'integration_ms': _INTEGRATION_MS[cfg & _CFG_TIM_MASK],
        }

    def set_thresholds(self, low_lux, high_lux):
        """Set the interrupt threshold window.

        Args:
            low_lux: Lower threshold in lux.
            high_lux: Upper threshold in lux.

        Raises:
            ValueError: if ``low_lux`` is greater than ``high_lux``.
        """
        if low_lux > high_lux:
            raise ValueError('low_lux must not exceed high_lux')
        self._write_reg(_REG_LOWER, _encode_threshold(low_lux))
        self._write_reg(_REG_UPPER, _encode_threshold(high_lux))

    def get_thresholds(self):
        """Read the interrupt threshold window.

        Returns:
            tuple: ``(low_lux, high_lux)``; the upper threshold carries the
            implied low nibble 1111.
        """
        low = self._read_reg(_REG_LOWER)
        high = self._read_reg(_REG_UPPER)
        return _decode_threshold(low, False), _decode_threshold(high, True)

    def set_threshold_delay(self, seconds):
        """Set how long the light must stay outside the window before the interrupt.

        Args:
            seconds: Delay in seconds, 0–25.5 (resolution 0.1 s).
        """
        value = int(seconds / 0.1 + 0.5)
        self._write_reg(_REG_TIMER, max(0, min(255, value)))

    def get_threshold_delay(self):
        """Read the threshold delay.

        Returns:
            float: Delay in seconds.
        """
        return self._read_reg(_REG_TIMER) * 0.1

    def enable_interrupt(self):
        """Enable the interrupt (INT_ENABLE <- 0x01)."""
        self._write_reg(_REG_INT_ENABLE, 0x01)

    def disable_interrupt(self):
        """Disable the interrupt (INT_ENABLE <- 0x00; also clears a pending status)."""
        self._write_reg(_REG_INT_ENABLE, 0x00)

    def poll_interrupt(self):
        """Read INT_STATUS (clearing it).

        Returns:
            int: Status; bit 0 (``SOURCE_THRESHOLD``) is set if the light level
            was outside the threshold window for longer than the delay.
        """
        return self._read_reg(_REG_INT_STATUS) & 0x01

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to INT (falling edge).

        ``callback(status)`` is called after the driver has read (and thereby
        cleared) INT_STATUS. Delivery uses int_pin, else connection.int_pin,
        else a 100 ms polling thread (Linux).

        Args:
            callback: Callable(status: int).
            int_pin: Optional InputPin overriding connection.int_pin.
        """
        self._callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if pin is not None:
            from periph.connection.input_pin import InputPin
            pin.on_edge(self._int_handler, InputPin.FALLING)
            self._int_source = pin
        elif _LINUX:
            self._poll_stop = False
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()

    def off_interrupt(self):
        """Unsubscribe and stop delivery."""
        self._poll_stop = True
        src = self._int_source
        if src is not None:
            src.off_edge(self._int_handler)
            self._int_source = None
        self._callback = None

    def _int_handler(self):
        status = self.poll_interrupt()
        if status and self._callback:
            self._callback(status)

    def _poll_loop(self):
        while not self._poll_stop:
            self._int_handler()
            time.sleep(_POLL_INTERVAL_S)
