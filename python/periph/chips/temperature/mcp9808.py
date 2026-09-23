"""MCP9808 — ±0.5°C maximum accuracy digital temperature sensor (Microchip).

Band-gap temperature sensor with a delta-sigma ADC, read over I2C. Measures
ambient temperature with a user-selectable resolution (0.5°C down to
0.0625°C), and drives an open-drain Alert output from three programmable
0.25°C-resolution boundaries (TUPPER/TLOWER/TCRIT) with optional hysteresis.
Eight selectable addresses (0x18-0x1F) via the A0/A1/A2 strap pins. The same
driver file is used on MicroPython, CircuitPython, and Linux hosts.

Registers are 16-bit, big-endian, addressed through a non-incrementing
Register Pointer.

Args:
    connection: Configured I2C connection pointing at the device
        (0x18-0x1F, per the board's A0/A1/A2 strapping).
"""

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False


I2C_ADDRESS = 0x18

MANUFACTURER_ID = 0x0054
DEVICE_ID = 0x04

# Interrupt sources — the three boundary-status bits of TA (bits 15:13).
SOURCE_LOWER    = 0x01
SOURCE_UPPER    = 0x02
SOURCE_CRITICAL = 0x04

# Measurement resolutions in °C, indexed by the RESOLUTION register code.
_RESOLUTIONS = (0.5, 0.25, 0.125, 0.0625)

# Hysteresis values in °C, indexed by THYST[1:0].
_HYSTERESES = (0.0, 1.5, 3.0, 6.0)

# Boundary registers hold an 11-bit two's-complement value (bits 12:2) in
# 0.25°C steps: -256.0 .. +255.75°C.
_LIMIT_MIN_QUARTERS = -1024
_LIMIT_MAX_QUARTERS = 1023


def _decode_temperature(raw16):
    raw = raw16 & 0x1FFF
    if raw & 0x1000:
        raw -= 0x2000
    return raw / 16.0


def _decode_limit(raw16):
    value = (raw16 >> 2) & 0x3FF
    if raw16 & 0x1000:
        value -= 1024
    return value / 4.0


def _encode_limit(celsius):
    quarters = int(celsius * 4.0 + 0.5) if celsius >= 0 else -int(-celsius * 4.0 + 0.5)
    if quarters < _LIMIT_MIN_QUARTERS:
        quarters = _LIMIT_MIN_QUARTERS
    elif quarters > _LIMIT_MAX_QUARTERS:
        quarters = _LIMIT_MAX_QUARTERS
    return (quarters & 0x7FF) << 2


class MCP9808Minimal:
    """MCP9808 ±0.5°C maximum accuracy digital temperature sensor — minimal interface.

    Reads ambient temperature. No register writes at construction: the
    power-on default (continuous conversion at 0.0625°C resolution, Alert
    output disabled) already serves the primary use case.

    Args:
        connection: Configured I2C connection pointing at the device.

    Raises:
        ValueError: If the manufacturer or device ID does not match.
    """

    _REG_CONFIG     = 0x01
    _REG_TUPPER     = 0x02
    _REG_TLOWER     = 0x03
    _REG_TCRIT      = 0x04
    _REG_TA         = 0x05
    _REG_MFR_ID     = 0x06
    _REG_DEVICE_ID  = 0x07
    _REG_RESOLUTION = 0x08

    def __init__(self, connection):
        self._connection = connection
        mfr = self._read_reg(self._REG_MFR_ID)
        if mfr != MANUFACTURER_ID:
            raise ValueError('MCP9808 not found: expected MANUFACTURER_ID 0x{:04X}, got 0x{:04X}'.format(
                MANUFACTURER_ID, mfr))
        dev = self._read_reg(self._REG_DEVICE_ID) >> 8
        if dev != DEVICE_ID:
            raise ValueError('MCP9808 not found: expected DEVICE_ID 0x{:02X}, got 0x{:02X}'.format(
                DEVICE_ID, dev))

    def _read_reg(self, reg):
        data = self._connection.write_read(bytes([reg]), 2)
        return (data[0] << 8) | data[1]

    def _write_reg(self, reg, value):
        self._connection.write(bytes([reg, (value >> 8) & 0xFF, value & 0xFF]))

    def read_temperature(self):
        """Read the ambient temperature.

        Masks off TA's three boundary-status bits and decodes the 13-bit
        two's-complement value (0.0625°C per LSB).

        Returns:
            float: Ambient temperature in °C.
        """
        return _decode_temperature(self._read_reg(self._REG_TA))


class MCP9808Full(MCP9808Minimal):
    """MCP9808 full interface — extends Minimal with resolution control,
    Shutdown mode, the three alert boundaries, hysteresis, the one-way
    register locks, and the Level-2 Alert/interrupt API.

    Args:
        connection: Configured I2C connection pointing at the device.

    Raises:
        ValueError: If the manufacturer or device ID does not match.
    """

    # CONFIG (0x01) bits.
    _CFG_THYST_SHIFT = 9
    _CFG_THYST_MASK  = 0x0600
    _CFG_SHDN        = 0x0100
    _CFG_CRIT_LOCK   = 0x0080
    _CFG_WIN_LOCK    = 0x0040
    _CFG_INT_CLEAR   = 0x0020
    _CFG_ALERT_STAT  = 0x0010
    _CFG_ALERT_CNT   = 0x0008
    _CFG_ALERT_SEL   = 0x0004
    _CFG_ALERT_POL   = 0x0002
    _CFG_ALERT_MOD   = 0x0001
    _CFG_LOCKS       = 0x00C0
    # Writable bits: everything but the unimplemented 15:11, the read-only
    # ALERT_STAT, and the self-clearing INT_CLEAR (set only on purpose).
    _CFG_WRITE_MASK  = 0x07CF

    def __init__(self, connection):
        super().__init__(connection)
        self._callback = None
        self._int_pin_used = None
        self._poll_stop = False
        self._poll_thread = None
        self._last_status = 0

    def _read_config(self):
        return self._read_reg(self._REG_CONFIG) & self._CFG_WRITE_MASK

    def _write_config(self, value):
        self._write_reg(self._REG_CONFIG, value & self._CFG_WRITE_MASK)

    # --- Resolution -------------------------------------------------------

    def set_resolution(self, celsius):
        """Set the measurement resolution.

        Finer steps take longer to convert: 0.5°C = 30 ms, 0.25°C = 65 ms,
        0.125°C = 130 ms, 0.0625°C = 250 ms (typical).

        Args:
            celsius: One of 0.5, 0.25, 0.125, 0.0625.

        Raises:
            ValueError: If celsius is not a supported step.
        """
        for code, step in enumerate(_RESOLUTIONS):
            if abs(celsius - step) < 1e-6:
                self._connection.write(bytes([self._REG_RESOLUTION, code]))
                return
        raise ValueError('resolution must be one of 0.5, 0.25, 0.125, 0.0625')

    def get_resolution(self):
        """Read the measurement resolution.

        Returns:
            float: Resolution step in °C.
        """
        code = self._connection.write_read(bytes([self._REG_RESOLUTION]), 1)[0] & 0x03
        return _RESOLUTIONS[code]

    # --- Shutdown ---------------------------------------------------------

    def shutdown(self):
        """Enter Shutdown (low-power) mode; TA holds its last value.

        No-op while either lock bit is set (the chip ignores SHDN=1 then).
        """
        config = self._read_config()
        if config & self._CFG_LOCKS:
            return
        self._write_config(config | self._CFG_SHDN)

    def wake(self):
        """Leave Shutdown mode and resume continuous conversion."""
        self._write_config(self._read_config() & ~self._CFG_SHDN)

    def is_shutdown(self):
        """Report whether the sensor is in Shutdown mode.

        Returns:
            bool: True if SHDN is set.
        """
        return bool(self._read_reg(self._REG_CONFIG) & self._CFG_SHDN)

    # --- Boundaries -------------------------------------------------------

    def get_upper_limit(self):
        """Read the TUPPER boundary.

        Returns:
            float: Upper boundary in °C (0.25°C steps).
        """
        return _decode_limit(self._read_reg(self._REG_TUPPER))

    def set_upper_limit(self, celsius):
        """Write the TUPPER boundary, rounded to the nearest 0.25°C.

        Ignored by the chip while WIN_LOCK is set.

        Args:
            celsius: Upper boundary in °C (-256.0 to 255.75, clamped).
        """
        self._write_reg(self._REG_TUPPER, _encode_limit(celsius))

    def get_lower_limit(self):
        """Read the TLOWER boundary.

        Returns:
            float: Lower boundary in °C (0.25°C steps).
        """
        return _decode_limit(self._read_reg(self._REG_TLOWER))

    def set_lower_limit(self, celsius):
        """Write the TLOWER boundary, rounded to the nearest 0.25°C.

        Ignored by the chip while WIN_LOCK is set.

        Args:
            celsius: Lower boundary in °C (-256.0 to 255.75, clamped).
        """
        self._write_reg(self._REG_TLOWER, _encode_limit(celsius))

    def get_critical_limit(self):
        """Read the TCRIT boundary.

        Returns:
            float: Critical boundary in °C (0.25°C steps).
        """
        return _decode_limit(self._read_reg(self._REG_TCRIT))

    def set_critical_limit(self, celsius):
        """Write the TCRIT boundary, rounded to the nearest 0.25°C.

        Ignored by the chip while CRIT_LOCK is set.

        Args:
            celsius: Critical boundary in °C (-256.0 to 255.75, clamped).
        """
        self._write_reg(self._REG_TCRIT, _encode_limit(celsius))

    # --- Hysteresis -------------------------------------------------------

    def set_hysteresis(self, celsius):
        """Set the boundary hysteresis (applies to the cooling edge only).

        Ignored by the chip while either lock bit is set.

        Args:
            celsius: One of 0, 1.5, 3.0, 6.0.

        Raises:
            ValueError: If celsius is not a supported value.
        """
        for code, value in enumerate(_HYSTERESES):
            if abs(celsius - value) < 1e-6:
                config = self._read_config() & ~self._CFG_THYST_MASK
                self._write_config(config | (code << self._CFG_THYST_SHIFT))
                return
        raise ValueError('hysteresis must be one of 0, 1.5, 3.0, 6.0')

    def get_hysteresis(self):
        """Read the boundary hysteresis.

        Returns:
            float: Hysteresis in °C.
        """
        config = self._read_reg(self._REG_CONFIG)
        return _HYSTERESES[(config & self._CFG_THYST_MASK) >> self._CFG_THYST_SHIFT]

    # --- Locks ------------------------------------------------------------

    def lock_critical_limit(self):
        """Lock TCRIT (and ALERT_SEL/POL/MOD) until the next power-on reset.

        Irreversible except by power-on reset.
        """
        self._write_config(self._read_config() | self._CFG_CRIT_LOCK)

    def lock_window_limits(self):
        """Lock TUPPER/TLOWER (and ALERT_SEL/POL/MOD) until the next power-on reset.

        Irreversible except by power-on reset.
        """
        self._write_config(self._read_config() | self._CFG_WIN_LOCK)

    def is_critical_limit_locked(self):
        """Report whether CRIT_LOCK is set.

        Returns:
            bool: True if TCRIT is locked.
        """
        return bool(self._read_reg(self._REG_CONFIG) & self._CFG_CRIT_LOCK)

    def is_window_limits_locked(self):
        """Report whether WIN_LOCK is set.

        Returns:
            bool: True if TUPPER/TLOWER are locked.
        """
        return bool(self._read_reg(self._REG_CONFIG) & self._CFG_WIN_LOCK)

    # --- Alert output -----------------------------------------------------

    def configure_alert(self, mode='all', output='comparator', polarity='active_low'):
        """Configure the Alert output's source, mode, and polarity.

        Args:
            mode: 'all' (TUPPER/TLOWER/TCRIT) or 'critical_only' (TCRIT only).
            output: 'comparator' or 'interrupt'.
            polarity: 'active_low' (needs pull-up) or 'active_high'.

        Raises:
            ValueError: If an argument is not one of the listed values.
            RuntimeError: If either lock bit is set (the bits are frozen).
        """
        if mode not in ('all', 'critical_only'):
            raise ValueError("mode must be 'all' or 'critical_only'")
        if output not in ('comparator', 'interrupt'):
            raise ValueError("output must be 'comparator' or 'interrupt'")
        if polarity not in ('active_low', 'active_high'):
            raise ValueError("polarity must be 'active_low' or 'active_high'")
        config = self._read_config()
        if config & self._CFG_LOCKS:
            raise RuntimeError('MCP9808: Alert configuration is locked until power-on reset')
        config &= ~(self._CFG_ALERT_SEL | self._CFG_ALERT_POL | self._CFG_ALERT_MOD)
        if mode == 'critical_only':
            config |= self._CFG_ALERT_SEL
        if polarity == 'active_high':
            config |= self._CFG_ALERT_POL
        if output == 'interrupt':
            config |= self._CFG_ALERT_MOD
        self._write_config(config)

    def enable_alert(self):
        """Enable the Alert output (ALERT_CNT=1)."""
        self._write_config(self._read_config() | self._CFG_ALERT_CNT)

    def disable_alert(self):
        """Disable the Alert output (ALERT_CNT=0)."""
        self._write_config(self._read_config() & ~self._CFG_ALERT_CNT)

    def is_alert_asserted(self):
        """Report whether the Alert output is currently asserted.

        Returns:
            bool: True if ALERT_STAT is set.
        """
        return bool(self._read_reg(self._REG_CONFIG) & self._CFG_ALERT_STAT)

    def clear_interrupt(self):
        """Clear an asserted interrupt-mode Alert output (INT_CLEAR=1).

        Has no effect in comparator mode.
        """
        self._write_reg(self._REG_CONFIG, self._read_config() | self._CFG_INT_CLEAR)

    # --- Interrupt API ----------------------------------------------------

    def poll_interrupt(self):
        """Read TA's live boundary-status bits.

        Nothing is cleared — the bits are a live comparison, always current.

        Returns:
            int: Mask of SOURCE_LOWER / SOURCE_UPPER / SOURCE_CRITICAL.
        """
        return (self._read_reg(self._REG_TA) >> 13) & 0x07

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to Alert events.

        Delivery: if int_pin is given, it is wired directly. Otherwise falls
        back to connection.int_pin, or a 5 ms polling thread on Linux if
        neither is available. With a pin, the callback runs on every Alert
        edge (the edge direction follows the configured ALERT_POL). The
        polling fallback calls it whenever the status mask changes. The
        Alert output must be enabled (enable_alert()) for a pin to see edges.

        Args:
            callback: Callable(status: int) — the poll_interrupt() mask;
                test it against the SOURCE_* constants.
            int_pin: Optional InputPin for this call, overriding connection.int_pin.
        """
        self._callback = callback
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        self._int_pin_used = pin
        if pin is not None:
            from periph.connection.input_pin import InputPin
            active_high = self._read_reg(self._REG_CONFIG) & self._CFG_ALERT_POL
            pin.on_edge(self._int_handler, InputPin.RISING if active_high else InputPin.FALLING)
        elif _LINUX:
            self._last_status = self.poll_interrupt()
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
        status = self.poll_interrupt()
        if self._callback:
            self._callback(status)

    def _poll_loop(self):
        import time
        while not self._poll_stop:
            status = self.poll_interrupt()
            if status != self._last_status:
                self._last_status = status
                if self._callback:
                    self._callback(status)
            time.sleep(0.005)
