"""TMP102 — 12-bit digital temperature sensor with ALERT output (Texas Instruments).

Two-wire temperature sensor with 0.0625°C resolution, ±0.5°C typical
accuracy and a thermostat output driven by programmable TLOW/THIGH limits.
Register-compatible with the TMP75/LM75 family. Four selectable addresses
(0x48-0x4B) via the ADD0 pin. The same driver file is used on MicroPython,
CircuitPython, and Linux hosts.

Registers are 16-bit, big-endian, addressed through a persistent pointer.

Args:
    connection: RegisterConnection (I²C or SMBus) pointing at the device
        (0x48-0x4B, per the board's ADD0 strapping).
"""

import time

from periph.connection.register import to_signed

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False


I2C_ADDRESS = 0x48

_RESOLUTION = 0.0625

# Limit range in °C (datasheet: -55...+150 °C in extended mode).
_LIMIT_MIN = -55.0
_LIMIT_MAX = 150.0

_CONVERSION_RATES = (0.25, 1.0, 4.0, 8.0)
_FAULT_QUEUES = (1, 2, 4, 6)


def _decode_temperature(raw16):
    """Decode a TEMPERATURE/limit register; bit 0 = 1 marks the 13-bit format."""
    if raw16 & 0x0001:
        return to_signed(raw16 >> 3, 13) * _RESOLUTION
    return to_signed(raw16 >> 4, 12) * _RESOLUTION


class TMP102Minimal:
    """TMP102 12-bit digital temperature sensor — minimal interface.

    Reads the temperature. No bus access at construction: the power-on
    default (continuous conversion at 4 Hz, comparator mode, 12-bit)
    already serves the primary use case.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
    """

    _REG_TEMPERATURE = 0x00
    _REG_CONFIG      = 0x01
    _REG_T_LOW       = 0x02
    _REG_T_HIGH      = 0x03

    def __init__(self, connection):
        self._connection = connection

    def _read_reg(self, reg):
        data = self._connection.read_reg(reg, 2)
        return (data[0] << 8) | data[1]

    def _write_reg(self, reg, value):
        self._connection.write_reg(reg, bytes([(value >> 8) & 0xFF, value & 0xFF]))

    def read_temperature(self):
        """Read the temperature.

        Decodes the 12-bit (0.0625°C per LSB) or, in extended mode, the 13-bit
        two's-complement value; the format is taken from bit 0 of the result.

        Returns:
            float: Temperature in °C.
        """
        return _decode_temperature(self._read_reg(self._REG_TEMPERATURE))


class TMP102Full(TMP102Minimal):
    """TMP102 full interface — extends Minimal with conversion rate, extended
    mode, the TLOW/THIGH limits, fault queue, polarity, thermostat mode,
    shutdown/one-shot, alert status, general-call reset and the ALERT
    interrupt API.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
    """

    # CONFIGURATION (0x01) bits.
    _CFG_OS       = 0x8000
    _CFG_F_SHIFT  = 11
    _CFG_F_MASK   = 0x1800
    _CFG_POL      = 0x0400
    _CFG_TM       = 0x0200
    _CFG_SD       = 0x0100
    _CFG_CR_SHIFT = 6
    _CFG_CR_MASK  = 0x00C0
    _CFG_AL       = 0x0020
    _CFG_EM       = 0x0010
    _CFG_WRITABLE = 0x9FD0
    _CFG_RESERVED = 0x6000   # R1:R0, read-only, always 11

    def __init__(self, connection):
        super().__init__(connection)
        self._callback = None
        self._int_pin_used = None
        self._poll_stop = False
        self._poll_thread = None

    def _update_config(self, clear, setbits):
        config = (self.read_config() & ~clear) | setbits
        self.write_config(config)

    def read_raw(self):
        """Read the raw signed temperature count.

        Returns:
            int: Signed 12-bit (13-bit in extended mode) count; the
                temperature is ``count * 0.0625`` °C.
        """
        raw16 = self._read_reg(self._REG_TEMPERATURE)
        if raw16 & 0x0001:
            return to_signed(raw16 >> 3, 13)
        return to_signed(raw16 >> 4, 12)

    def read_config(self):
        """Read the 16-bit CONFIGURATION register.

        Returns:
            int: Register value (reset value 0x60A0).
        """
        return self._read_reg(self._REG_CONFIG)

    def write_config(self, value):
        """Write the 16-bit CONFIGURATION register.

        Read-only bits (R1:R0, AL, bits 3:0) are masked out before the write.

        Args:
            value: Register value; only OS, F1:F0, POL, TM, SD, CR1:CR0 and
                EM are applied.
        """
        self._write_reg(self._REG_CONFIG, (value & self._CFG_WRITABLE) | self._CFG_RESERVED)

    def set_conversion_rate(self, hz):
        """Set the conversion rate.

        Args:
            hz: 0.25, 1, 4 or 8 Hz.

        Raises:
            ValueError: If hz is not one of the supported rates.
        """
        if hz not in _CONVERSION_RATES:
            raise ValueError('hz must be 0.25, 1, 4 or 8')
        code = _CONVERSION_RATES.index(hz)
        self._update_config(self._CFG_CR_MASK, code << self._CFG_CR_SHIFT)

    def set_extended_mode(self, on):
        """Enable or disable the 13-bit extended mode (EM bit).

        Args:
            on: True for 13-bit (-256...+255.9375°C), False for 12-bit.
        """
        self._update_config(self._CFG_EM, self._CFG_EM if on else 0)

    def _encode_limit(self, celsius, extended):
        if celsius < _LIMIT_MIN:
            celsius = _LIMIT_MIN
        elif celsius > _LIMIT_MAX:
            celsius = _LIMIT_MAX
        counts = int(celsius / _RESOLUTION + 0.5) if celsius >= 0 else -int(-celsius / _RESOLUTION + 0.5)
        if extended:
            return (counts << 3) & 0xFFF8
        if counts > 2047:
            counts = 2047
        return (counts << 4) & 0xFFF0

    def _decode_limit(self, raw16, extended):
        if extended:
            return to_signed(raw16 >> 3, 13) * _RESOLUTION
        return to_signed(raw16 >> 4, 12) * _RESOLUTION

    def _set_limit(self, reg, celsius):
        extended = bool(self.read_config() & self._CFG_EM)
        self._write_reg(reg, self._encode_limit(celsius, extended))

    def _get_limit(self, reg):
        extended = bool(self.read_config() & self._CFG_EM)
        return self._decode_limit(self._read_reg(reg), extended)

    def set_t_high(self, celsius):
        """Set the THIGH limit (reset 80°C).

        Args:
            celsius: Limit in °C, rounded to 0.0625°C and clamped to
                -55...+150°C (12-bit mode: up to +127.9375°C).
        """
        self._set_limit(self._REG_T_HIGH, celsius)

    def get_t_high(self):
        """Read the THIGH limit.

        Returns:
            float: Limit in °C.
        """
        return self._get_limit(self._REG_T_HIGH)

    def set_t_low(self, celsius):
        """Set the TLOW limit (reset 75°C).

        Args:
            celsius: Limit in °C, rounded to 0.0625°C and clamped to
                -55...+150°C (12-bit mode: up to +127.9375°C).
        """
        self._set_limit(self._REG_T_LOW, celsius)

    def get_t_low(self):
        """Read the TLOW limit.

        Returns:
            float: Limit in °C.
        """
        return self._get_limit(self._REG_T_LOW)

    def set_fault_queue(self, count):
        """Set the number of consecutive faults that trigger ALERT.

        Args:
            count: 1, 2, 4 or 6.

        Raises:
            ValueError: If count is not one of the supported values.
        """
        if count not in _FAULT_QUEUES:
            raise ValueError('count must be 1, 2, 4 or 6')
        code = _FAULT_QUEUES.index(count)
        self._update_config(self._CFG_F_MASK, code << self._CFG_F_SHIFT)

    def set_polarity(self, active_high):
        """Set the ALERT polarity (POL bit).

        Args:
            active_high: True for active-high, False for active-low (default).
        """
        self._update_config(self._CFG_POL, self._CFG_POL if active_high else 0)

    def set_thermostat_mode(self, interrupt):
        """Select the thermostat mode (TM bit).

        Args:
            interrupt: True for interrupt mode, False for comparator mode (default).
        """
        self._update_config(self._CFG_TM, self._CFG_TM if interrupt else 0)

    def shutdown(self):
        """Shut the device down after the current conversion (SD=1)."""
        self._update_config(0, self._CFG_SD)

    def wake(self):
        """Resume continuous conversion (SD=0)."""
        self._update_config(self._CFG_SD, 0)

    def one_shot(self):
        """Take a single conversion and return the temperature.

        Puts the device in shutdown if needed, sets OS=1, polls OS until the
        conversion is done (30 ms timeout) and restores the previous mode.

        Returns:
            float: Temperature in °C.

        Raises:
            OSError: If the conversion does not finish within 30 ms.
        """
        config = self.read_config()
        was_running = not (config & self._CFG_SD)
        if was_running:
            config |= self._CFG_SD
            self.write_config(config)
        self.write_config(config | self._CFG_OS)
        done = False
        for _ in range(30):
            time.sleep(0.001)
            if self.read_config() & self._CFG_OS:
                done = True
                break
        temperature = self.read_temperature()
        if was_running:
            self.wake()
        if not done:
            raise OSError('TMP102: one-shot conversion timed out')
        return temperature

    def alert_state(self):
        """Report whether ALERT is currently active.

        Returns:
            bool: True while the comparator state is alerting (the AL bit
                corrected for POL).
        """
        config = self.read_config()
        al = bool(config & self._CFG_AL)
        # AL reads 1 while inactive with POL=0 (inverted); POL=1 flips it.
        return (not al) if not (config & self._CFG_POL) else al

    def reset(self):
        """Send the general-call reset (0x00 0x06).

        Resets every general-call device on the bus to power-up values. Needs
        a connection that transmits the bytes to the general-call address.
        """
        self._connection.write(bytes([0x00, 0x06]))

    # --- Interrupt API ----------------------------------------------------

    def poll_interrupt(self):
        """Read the alert state and clear an interrupt-mode alert.

        Reads CONFIGURATION, then TEMPERATURE (any register read clears an
        interrupt-mode ALERT).

        Returns:
            bool: True while the comparator state is alerting.
        """
        alerting = self.alert_state()
        self._read_reg(self._REG_TEMPERATURE)
        return alerting

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to ALERT events.

        Delivery: if int_pin is given, it is wired directly. Otherwise falls
        back to connection.int_pin, or a 1 s polling thread on Linux if
        neither is available. With a pin, the callback runs on every active
        ALERT edge (the direction follows POL); the polling fallback calls it
        when poll_interrupt() reports an alert.

        Args:
            callback: Callable(temperature_c: float).
            int_pin: Optional InputPin for this call, overriding connection.int_pin.
        """
        self._callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        self._int_pin_used = pin
        if pin is not None:
            from periph.connection.input_pin import InputPin
            active_high = self.read_config() & self._CFG_POL
            pin.on_edge(self._int_handler, InputPin.RISING if active_high else InputPin.FALLING)
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
        temperature = self.read_temperature()
        if self._callback:
            self._callback(temperature)

    def _poll_loop(self):
        while not self._poll_stop:
            if self.poll_interrupt() and self._callback:
                self._callback(self.read_temperature())
            time.sleep(1)
