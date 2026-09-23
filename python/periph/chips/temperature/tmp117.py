"""TMP117 — ±0.1°C high-accuracy, low-power digital temperature sensor (Texas Instruments).

NIST-traceable 16-bit temperature sensor (0.0078125°C per LSB) read over an
I2C/SMBus-compatible two-wire bus. Offers continuous, one-shot, and shutdown
conversion modes with selectable averaging and cycle time, a window-alert /
latching-Therm / Data-Ready ALERT output, and EEPROM persistence of its
configuration, limit, and offset registers plus general-purpose scratch
storage. Four selectable addresses (0x48-0x4B) via the 4-level ADD0 strap. The
same driver file is used on MicroPython, CircuitPython, and Linux hosts.

Registers are 16-bit, big-endian, addressed through a non-incrementing
Register Pointer.

Args:
    connection: Configured I2C connection pointing at the device
        (0x48-0x4B, per the board's ADD0 strapping).
"""

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False

import time


I2C_ADDRESS = 0x48

DEVICE_ID = 0x117

# Interrupt sources — CONFIGURATION's HIGH_Alert / LOW_Alert flags.
SOURCE_HIGH = 0x01
SOURCE_LOW  = 0x02

# Conversion cycle times in seconds, indexed by CONV[2:0] (no-averaging column).
_CYCLES = (0.0155, 0.125, 0.25, 0.5, 1.0, 4.0, 8.0, 16.0)

# Averaging counts, indexed by AVG[1:0].
_AVERAGINGS = (0, 8, 32, 64)

# Conversion modes, indexed by MOD[1:0] (0b10 reads back as continuous).
_MODES = ('continuous', 'shutdown', 'continuous', 'one_shot')

# EEPROM scratch slot -> register address.
_SCRATCH_REGS = {1: 0x05, 2: 0x06, 3: 0x08}

_LSB_C = 0.0078125


def _decode_temperature(raw16):
    raw = raw16 - 0x10000 if raw16 & 0x8000 else raw16
    return raw * _LSB_C


def _encode_temperature(celsius):
    steps = celsius / _LSB_C
    steps = int(steps + 0.5) if steps >= 0 else -int(-steps + 0.5)
    if steps < -32768:
        steps = -32768
    elif steps > 32767:
        steps = 32767
    return steps & 0xFFFF


class TMP117Minimal:
    """TMP117 ±0.1°C high-accuracy digital temperature sensor — minimal interface.

    Reads temperature. No register writes at construction: the POR/EEPROM
    default (continuous conversion, 8-conversion averaging, 1 s cycle, Alert
    mode) already serves the primary use case.

    Args:
        connection: Configured I2C connection pointing at the device.

    Raises:
        ValueError: If the DEVICE_ID register does not identify a TMP117.
    """

    _REG_TEMP_RESULT = 0x00
    _REG_CONFIG      = 0x01
    _REG_THIGH       = 0x02
    _REG_TLOW        = 0x03
    _REG_EEPROM_UL   = 0x04
    _REG_TEMP_OFFSET = 0x07
    _REG_DEVICE_ID   = 0x0F

    def __init__(self, connection):
        self._connection = connection
        did = self._read_reg(self._REG_DEVICE_ID) & 0x0FFF
        if did != DEVICE_ID:
            raise ValueError('TMP117 not found: expected DEVICE_ID 0x{:03X}, got 0x{:03X}'.format(
                DEVICE_ID, did))

    def _read_reg(self, reg):
        data = self._connection.write_read(bytes([reg]), 2)
        return (data[0] << 8) | data[1]

    def _write_reg(self, reg, value):
        self._connection.write(bytes([reg, (value >> 8) & 0xFF, value & 0xFF]))

    def read_temperature(self):
        """Read the temperature.

        Decodes TEMP_RESULT's 16-bit two's-complement value (0.0078125°C per
        LSB). Returns -256.0 until the first conversion after power-up
        completes.

        Returns:
            float: Temperature in °C.
        """
        return _decode_temperature(self._read_reg(self._REG_TEMP_RESULT))


class TMP117Full(TMP117Minimal):
    """TMP117 full interface — extends Minimal with conversion mode, averaging,
    and cycle-time control, one-shot triggering, both temperature limits, the
    calibration offset, soft reset, EEPROM persistence and scratch storage,
    and the Level-2 Alert/interrupt API.

    Args:
        connection: Configured I2C connection pointing at the device.

    Raises:
        ValueError: If the DEVICE_ID register does not identify a TMP117.
    """

    # CONFIGURATION (0x01) bits.
    _CFG_HIGH_ALERT  = 0x8000
    _CFG_LOW_ALERT   = 0x4000
    _CFG_DATA_READY  = 0x2000
    _CFG_EEPROM_BUSY = 0x1000
    _CFG_MOD_SHIFT   = 10
    _CFG_MOD_MASK    = 0x0C00
    _CFG_CONV_SHIFT  = 7
    _CFG_CONV_MASK   = 0x0380
    _CFG_AVG_SHIFT   = 5
    _CFG_AVG_MASK    = 0x0060
    _CFG_TNA         = 0x0010
    _CFG_POL         = 0x0008
    _CFG_DR_ALERT    = 0x0004
    _CFG_SOFT_RESET  = 0x0002
    # Writable bits: MOD/CONV/AVG/T-nA/POL/DR-Alert. Soft_Reset is set only on purpose.
    _CFG_WRITE_MASK  = 0x0FFC

    _MOD_ONE_SHOT = 0x03

    # EEPROM_UL (0x04) bits.
    _EUN         = 0x8000
    _EEPROM_BUSY = 0x4000

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

    # --- Conversion -------------------------------------------------------

    def configure(self, mode='continuous', averaging=8, cycle_seconds=1.0):
        """Set conversion mode, averaging, and cycle time.

        The cycle time is matched to the nearest CONV[2:0] step from the
        no-averaging column (15.5 ms, 125 ms, 250 ms, 500 ms, 1 s, 4 s, 8 s,
        16 s); at higher averaging the hardware lengthens short cycles
        automatically. The Alert configuration bits are preserved.

        Args:
            mode: 'continuous', 'shutdown', or 'one_shot'.
            averaging: Conversions averaged per result: 0, 8, 32, or 64.
            cycle_seconds: Desired conversion cycle time in s.

        Raises:
            ValueError: If mode or averaging is not one of the listed values.
        """
        if mode == 'continuous':
            mod = 0x00
        elif mode == 'shutdown':
            mod = 0x01
        elif mode == 'one_shot':
            mod = self._MOD_ONE_SHOT
        else:
            raise ValueError("mode must be 'continuous', 'shutdown', or 'one_shot'")
        if averaging not in _AVERAGINGS:
            raise ValueError('averaging must be one of 0, 8, 32, 64')
        avg = _AVERAGINGS.index(averaging)
        conv = 0
        for code, cycle in enumerate(_CYCLES):
            if abs(cycle - cycle_seconds) < abs(_CYCLES[conv] - cycle_seconds):
                conv = code
        config = self._read_config() & ~(self._CFG_MOD_MASK | self._CFG_CONV_MASK | self._CFG_AVG_MASK)
        config |= (mod << self._CFG_MOD_SHIFT) | (conv << self._CFG_CONV_SHIFT) | (avg << self._CFG_AVG_SHIFT)
        self._write_config(config)

    def get_config(self):
        """Read the conversion mode, averaging, and cycle time.

        Returns:
            tuple: (mode: str, averaging: int, cycle_seconds: float), where
            cycle_seconds is the no-averaging CONV[2:0] step.
        """
        config = self._read_reg(self._REG_CONFIG)
        mode = _MODES[(config & self._CFG_MOD_MASK) >> self._CFG_MOD_SHIFT]
        averaging = _AVERAGINGS[(config & self._CFG_AVG_MASK) >> self._CFG_AVG_SHIFT]
        cycle = _CYCLES[(config & self._CFG_CONV_MASK) >> self._CFG_CONV_SHIFT]
        return (mode, averaging, cycle)

    def is_shutdown(self):
        """Report whether the sensor is in Shutdown mode.

        Returns:
            bool: True if MOD[1:0] is Shutdown.
        """
        return (self._read_reg(self._REG_CONFIG) & self._CFG_MOD_MASK) >> self._CFG_MOD_SHIFT == 0x01

    def trigger_one_shot(self):
        """Start a single conversion (MOD[1:0]=One-Shot).

        The sensor returns to Shutdown once the conversion (including
        averaging) completes.
        """
        config = self._read_config() & ~self._CFG_MOD_MASK
        self._write_config(config | (self._MOD_ONE_SHOT << self._CFG_MOD_SHIFT))

    def is_data_ready(self):
        """Report whether a fresh conversion result is available.

        Reading this flag clears it (as does reading TEMP_RESULT).

        Returns:
            bool: True if Data_Ready is set.
        """
        return bool(self._read_reg(self._REG_CONFIG) & self._CFG_DATA_READY)

    # --- Limits and offset ------------------------------------------------

    def get_high_limit(self):
        """Read THIGH_LIMIT.

        Returns:
            float: High limit in °C.
        """
        return _decode_temperature(self._read_reg(self._REG_THIGH))

    def set_high_limit(self, celsius):
        """Write THIGH_LIMIT, rounded to the nearest 0.0078125°C.

        Args:
            celsius: High limit in °C (-256.0 to 255.9921875, clamped).
        """
        self._write_reg(self._REG_THIGH, _encode_temperature(celsius))

    def get_low_limit(self):
        """Read TLOW_LIMIT.

        Returns:
            float: Low limit in °C.
        """
        return _decode_temperature(self._read_reg(self._REG_TLOW))

    def set_low_limit(self, celsius):
        """Write TLOW_LIMIT, rounded to the nearest 0.0078125°C.

        In Therm mode this is HIGH_Alert's reset threshold (hysteresis).

        Args:
            celsius: Low limit in °C (-256.0 to 255.9921875, clamped).
        """
        self._write_reg(self._REG_TLOW, _encode_temperature(celsius))

    def get_temperature_offset(self):
        """Read TEMP_OFFSET.

        Returns:
            float: Calibration offset in °C.
        """
        return _decode_temperature(self._read_reg(self._REG_TEMP_OFFSET))

    def set_temperature_offset(self, celsius):
        """Write TEMP_OFFSET, added to every result after linearization.

        Args:
            celsius: Calibration offset in °C (-256.0 to 255.9921875, clamped).
        """
        self._write_reg(self._REG_TEMP_OFFSET, _encode_temperature(celsius))

    # --- Reset ------------------------------------------------------------

    def reset(self):
        """Software reset (Soft_Reset=1), then wait the 2 ms reset time.

        Reloads CONFIGURATION, THIGH_LIMIT, TLOW_LIMIT, and TEMP_OFFSET from
        EEPROM.
        """
        self._write_reg(self._REG_CONFIG, self._CFG_SOFT_RESET)
        time.sleep(0.002)

    # --- EEPROM -----------------------------------------------------------

    def unlock_eeprom(self):
        """Unlock the EEPROM (EUN=1).

        While unlocked, writes to CONFIGURATION, THIGH_LIMIT, TLOW_LIMIT,
        TEMP_OFFSET, and EEPROM2 also program the EEPROM as the new power-on
        default. Poll is_eeprom_busy() after each such write.
        """
        self._write_reg(self._REG_EEPROM_UL, self._EUN)

    def lock_eeprom(self):
        """Lock the EEPROM (EUN=0); register writes become volatile only."""
        self._write_reg(self._REG_EEPROM_UL, 0x0000)

    def is_eeprom_busy(self):
        """Report whether an EEPROM programming operation is in progress.

        Returns:
            bool: True if EEPROM_Busy is set.
        """
        return bool(self._read_reg(self._REG_EEPROM_UL) & self._EEPROM_BUSY)

    def read_eeprom_scratch(self, slot):
        """Read a general-purpose EEPROM scratch register.

        Args:
            slot: 1 (EEPROM1), 2 (EEPROM2), or 3 (EEPROM3). Slots 1 and 3
                hold factory NIST-traceability data.

        Returns:
            int: 16-bit register value.

        Raises:
            ValueError: If slot is not 1, 2, or 3.
        """
        if slot not in _SCRATCH_REGS:
            raise ValueError('slot must be 1, 2, or 3')
        return self._read_reg(_SCRATCH_REGS[slot])

    def write_eeprom_scratch(self, slot, value):
        """Write the general-purpose EEPROM2 scratch register.

        Only slot 2 is writable — EEPROM1/EEPROM3 hold factory
        NIST-traceability data. Persists across power cycles only while the
        EEPROM is unlocked.

        Args:
            slot: Must be 2.
            value: 16-bit value.

        Raises:
            ValueError: If slot is not 2.
        """
        if slot != 2:
            raise ValueError('only EEPROM scratch slot 2 is writable')
        self._write_reg(_SCRATCH_REGS[2], value & 0xFFFF)

    # --- Alert output -----------------------------------------------------

    def configure_alert(self, mode='alert', polarity='active_low', pin_function='alert'):
        """Configure the ALERT output's mode, polarity, and pin function.

        Args:
            mode: 'alert' (window alert) or 'therm' (latching thermostat;
                TLOW_LIMIT becomes the reset threshold).
            polarity: 'active_low' (needs pull-up) or 'active_high'.
            pin_function: 'alert' (alert/Therm status) or 'data_ready'.

        Raises:
            ValueError: If an argument is not one of the listed values.
        """
        if mode not in ('alert', 'therm'):
            raise ValueError("mode must be 'alert' or 'therm'")
        if polarity not in ('active_low', 'active_high'):
            raise ValueError("polarity must be 'active_low' or 'active_high'")
        if pin_function not in ('alert', 'data_ready'):
            raise ValueError("pin_function must be 'alert' or 'data_ready'")
        config = self._read_config() & ~(self._CFG_TNA | self._CFG_POL | self._CFG_DR_ALERT)
        if mode == 'therm':
            config |= self._CFG_TNA
        if polarity == 'active_high':
            config |= self._CFG_POL
        if pin_function == 'data_ready':
            config |= self._CFG_DR_ALERT
        self._write_config(config)

    # --- Interrupt API ----------------------------------------------------

    def poll_interrupt(self):
        """Read CONFIGURATION's HIGH_Alert / LOW_Alert flags.

        In Alert mode this read also clears both flags (a hardware side
        effect). In Therm mode HIGH_Alert clears only once the temperature
        drops below TLOW_LIMIT.

        Returns:
            int: Mask of SOURCE_HIGH / SOURCE_LOW.
        """
        config = self._read_reg(self._REG_CONFIG)
        status = 0
        if config & self._CFG_HIGH_ALERT:
            status |= SOURCE_HIGH
        if config & self._CFG_LOW_ALERT:
            status |= SOURCE_LOW
        return status

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to ALERT events.

        Delivery: if int_pin is given, it is wired directly. Otherwise falls
        back to connection.int_pin, or a 5 ms polling thread on Linux if
        neither is available. With a pin, the callback runs on every ALERT
        edge (the edge direction follows the configured polarity). The
        polling fallback calls it whenever the status mask changes.

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
            active_high = self._read_reg(self._REG_CONFIG) & self._CFG_POL
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
        while not self._poll_stop:
            status = self.poll_interrupt()
            if status != self._last_status:
                self._last_status = status
                if self._callback:
                    self._callback(status)
            time.sleep(0.005)
