try:
    import digitalio as _digitalio
    _CP = True
except ImportError:
    _CP = False

try:
    import threading as _threading
    _LINUX = True
except ImportError:
    _LINUX = False

_REG_INPUT = 0x00
_REG_OUTPUT = 0x01
_REG_POLARITY = 0x02
_REG_CONFIG = 0x03

_MASK = 0x0F  # bits 7:4 are unused


class Pca9536Minimal:
    """PCA9536 4-bit I/O expander (I²C / SMBus) — minimal interface.

    Provides 4 bidirectional GPIO pins (P0–P3) as GPIO objects via pin().
    CONFIG bit = 1 means input, 0 means output. Every I/O pin has a weak
    (about 100 kΩ) pull-up that is always on and cannot be switched; an
    undriven input reads 1. Only bits 3:0 of each register are used. A shadow of OUTPUT and CONFIG is kept so a single-pin change is
    one register write.

    Args:
        connection: RegisterConnection (I²C or SMBus, no PEC) for the device.
        addr: 7-bit I²C device address (fixed 0x41); informational, the
            connection owns the address.
    """

    IN  = 0
    OUT = 1

    def __init__(self, connection, addr=0x41):
        self._connection = connection
        self._addr = addr
        self._init_chip()

    # --- bus helpers -------------------------------------------------------

    def _reg_read(self, reg):
        return self._connection.read_reg(reg, 1)[0]

    def _reg_write(self, reg, value):
        self._connection.write_reg(reg, bytes([value & 0xFF]))

    def _init_chip(self):
        self._shadow = 0xFF
        self._direction = 0xFF
        self._polarity = 0x00
        self._reg_write(_REG_OUTPUT, 0xFF)
        self._reg_write(_REG_POLARITY, 0x00)
        self._reg_write(_REG_CONFIG, 0xFF)
        self._last_input = self._reg_read(_REG_INPUT) & _MASK

    def _write_output(self, mask):
        # Bits 7:4 are unused; keep them at their reset value (1).
        self._shadow = (mask & _MASK) | 0xF0
        self._reg_write(_REG_OUTPUT, self._shadow)

    def _read_port_raw(self):
        return self._reg_read(_REG_INPUT) & _MASK

    def _set_pin(self, n, value):
        if value:
            self._shadow |= (1 << n)
        else:
            self._shadow &= ~(1 << n)
        self._write_output(self._shadow)

    def _set_direction(self, n, is_input):
        # Latch the output level first so the pin never glitches.
        if is_input:
            self._direction |= (1 << n)
        else:
            self._reg_write(_REG_OUTPUT, self._shadow)
            self._direction &= ~(1 << n)
        self._reg_write(_REG_CONFIG, self._direction)

    def pin(self, n, mode=None):
        """Return a Pin proxy object for pin number n (0–3).

        Args:
            n: Pin index 0–3.
            mode: Optional direction to set immediately (IN or OUT).
                If omitted, the pin keeps its current CONFIG setting.

        Returns:
            _Pin (MicroPython/Linux) or _CPPin (CircuitPython).
        """
        if not 0 <= n <= 3:
            raise ValueError('pin must be 0-3')
        if _CP:
            return self._CPPin(self, n)
        p = self._Pin(self, n)
        if mode is not None:
            p.init(mode)
        return p

    def read_port(self, port=0):
        """Read all 4 pins as a bitmask (INPUT register, actual pin levels).

        Args:
            port: Ignored; the chip has a single port.

        Returns:
            int: 4-bit bitmask; bit 0 = P0.
        """
        return self._read_port_raw()

    def write_port(self, port=0, mask=0):
        """Write all 4 output bits via OUTPUT and update the shadow register.

        Bits of input pins are stored but have no effect until the pin
        becomes an output.

        Args:
            port: Ignored; the chip has a single port.
            mask: 4-bit output mask; bit 0 = P0.
        """
        self._write_output(mask)

    class _Pin:
        """GPIO proxy for a single pin — machine.Pin-compatible.

        Obtain via ``pin(n)``. Do not instantiate directly.
        """

        IN  = 0
        OUT = 1

        def __init__(self, chip, n):
            self._chip = chip
            self._n = n

        def init(self, mode, pull=None):
            """Set pin direction.

            Args:
                mode: IN (0) or OUT (1).
                pull: Must be None; the weak pull-up is always on and cannot be
                    switched.

            Raises:
                AttributeError: if a pull is requested.
            """
            if pull is not None:
                raise AttributeError('PCA9536 pull-up is fixed in hardware')
            self._chip._set_direction(self._n, mode == Pca9536Minimal.IN)

        def value(self, x=None):
            """Read or write the pin.

            Args:
                x: None to read; 0 or 1 to write.

            Returns:
                int: Logic level (0 or 1) when reading; None when writing.
            """
            if x is None:
                return (self._chip._read_port_raw() >> self._n) & 1
            self._chip._set_pin(self._n, x)

        def on(self):
            """Set pin high (output latch = 1)."""
            self._chip._set_pin(self._n, 1)

        def off(self):
            """Drive pin low (output latch = 0)."""
            self._chip._set_pin(self._n, 0)

        def toggle(self):
            """Invert the current output latch bit for this pin."""
            self._chip._set_pin(self._n, 1 - ((self._chip._shadow >> self._n) & 1))

    class _CPPin:
        """GPIO proxy for a single pin — digitalio.DigitalInOut-compatible.

        Obtain via ``pin(n)``. Do not instantiate directly.
        """

        def __init__(self, chip, n):
            self._chip = chip
            self._n = n
            self._direction = _digitalio.Direction.INPUT

        @property
        def direction(self):
            return self._direction

        @direction.setter
        def direction(self, d):
            self._direction = d
            self._chip._set_direction(self._n, d == _digitalio.Direction.INPUT)

        @property
        def value(self):
            return bool((self._chip._read_port_raw() >> self._n) & 1)

        @value.setter
        def value(self, v):
            self._chip._set_pin(self._n, int(bool(v)))

        @property
        def pull(self):
            return None

        @pull.setter
        def pull(self, v):
            if v is not None:
                raise AttributeError('PCA9536 pull-up is fixed in hardware (no pull-up/down control)')

        def switch_to_input(self, pull=None):
            self.pull = pull
            self.direction = _digitalio.Direction.INPUT

        def switch_to_output(self, value=False, drive_mode=None):
            if value:
                self._chip._set_pin(self._n, 1)
            self.direction = _digitalio.Direction.OUTPUT

        def deinit(self):
            pass


class Pca9536Full(Pca9536Minimal):
    """PCA9536 full interface — polarity, whole-port direction, change polling, raw access.

    The chip has no interrupt output, so change detection is by polling
    (``poll_changes`` / ``watch_changes``); ``Pin.watch`` is built on it.

    Args:
        connection: RegisterConnection (I²C or SMBus, no PEC) for the device.
        addr: 7-bit I²C device address (fixed 0x41).
    """

    IRQ_RISING  = 0x01
    IRQ_FALLING = 0x02

    def __init__(self, connection, addr=0x41):
        Pca9536Minimal.__init__(self, connection, addr)
        self._callback = None
        self._poll_thread = None
        self._poll_stop = True
        self._pin_handlers = {}
        self._pin_dispatch = False
        self._last_level = self._last_input

    def configure_polarity(self, mask):
        """Set input polarity inversion (POLARITY ← mask).

        The cached input value is refreshed, so the polarity change itself is
        not reported by ``poll_changes``.

        Args:
            mask: 4-bit mask; bit n = 1 inverts the INPUT bit of Pn.
        """
        self._polarity = mask & _MASK
        self._reg_write(_REG_POLARITY, self._polarity)
        self._last_input = self._reg_read(_REG_INPUT) & _MASK

    def get_direction(self):
        """Return the shadow CONFIG register (1 = input).

        Returns:
            int: 4-bit direction mask.
        """
        return self._direction & _MASK

    def set_direction(self, mask):
        """Write CONFIG in one transaction (1 = input, 0 = output).

        For pins turning into outputs the OUTPUT bits are written first so
        the pins never glitch. The cached input value is refreshed afterwards.

        Args:
            mask: 4-bit direction mask; bit n = 1 makes Pn an input.
        """
        self._reg_write(_REG_OUTPUT, self._shadow)
        self._direction = (mask & _MASK) | 0xF0
        self._reg_write(_REG_CONFIG, self._direction)
        self._last_input = self._reg_read(_REG_INPUT) & _MASK

    def read_register(self, reg):
        """Read a raw register (debug / escape hatch).

        Args:
            reg: Register address 0–3.

        Returns:
            int: Register value.

        Raises:
            ValueError: if reg is outside 0–3.
        """
        if not 0 <= reg <= 3:
            raise ValueError('register must be 0-3')
        return self._reg_read(reg)

    def write_register(self, reg, value):
        """Write a raw register (1–3); OUTPUT / POLARITY / CONFIG shadows follow.

        Args:
            reg: Register address 1–3 (INPUT, 0, is read-only).
            value: 8-bit value.

        Raises:
            ValueError: if reg is 0 or outside 1–3.
        """
        if not 1 <= reg <= 3:
            raise ValueError('register must be 1-3 (INPUT is read-only)')
        value &= 0xFF
        if reg == _REG_OUTPUT:
            self._shadow = value
        elif reg == _REG_POLARITY:
            self._polarity = value
        else:
            self._direction = value
        self._reg_write(reg, value)

    def reset(self):
        """Write the reset values and refresh the input cache.

        OUTPUT ← 0xFF, POLARITY ← 0x00, CONFIG ← 0xFF. The chip has no RESET
        pin; only a power cycle clears a lock-up.
        """
        self.unwatch_changes()
        self._init_chip()

    def poll_changes(self):
        """Read INPUT and return the changed-pin mask (software edge detection).

        The mask is the XOR of the new and previously cached INPUT value,
        limited to pins configured as inputs. The new value is cached.

        Returns:
            int: 4-bit mask; bit n = 1 if input Pn changed.
        """
        new = self._reg_read(_REG_INPUT) & _MASK
        changed = (new ^ self._last_input) & self._direction & _MASK
        self._last_input = new
        self._last_level = new
        return changed

    def watch_changes(self, callback, interval_s=0.005):
        """Start a polling thread that reports input changes.

        ``callback(changed_mask)`` is invoked whenever ``poll_changes()``
        returns a non-zero mask.

        Args:
            callback: Callable(changed_mask: int).
            interval_s: Polling interval in seconds (default 5 ms).

        Raises:
            RuntimeError: if the platform has no threading support.
        """
        if not _LINUX:
            raise RuntimeError('watch_changes needs threading support')
        self.unwatch_changes()
        self._callback = callback
        self._interval = interval_s
        self._poll_stop = False
        self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
        self._poll_thread.start()

    def unwatch_changes(self):
        """Stop the polling thread started by ``watch_changes``."""
        self._poll_stop = True
        t = self._poll_thread
        self._poll_thread = None
        if t is not None and t is not _threading.current_thread():
            t.join(1)
        self._callback = None
        self._pin_dispatch = False

    def _poll_loop(self):
        import time
        while not self._poll_stop:
            changed = self.poll_changes()
            cb = self._callback
            if changed and cb:
                cb(changed)
            time.sleep(self._interval)

    def _watch_pin(self, n, handler, trigger):
        if (self._direction >> n) & 1 == 0:
            raise ValueError('cannot watch an output pin')
        self._pin_handlers[n] = (handler, trigger)
        if not self._pin_dispatch:
            self.watch_changes(self._dispatch_pins)
            self._pin_dispatch = True

    def _unwatch_pin(self, n):
        self._pin_handlers.pop(n, None)

    def _dispatch_pins(self, changed):
        level = self._last_level
        for n, (handler, trigger) in list(self._pin_handlers.items()):
            if not (changed >> n) & 1:
                continue
            current = (level >> n) & 1
            if trigger is None or current == (1 if trigger == self.IRQ_RISING else 0):
                handler(self.pin(n))

    class _Pin(Pca9536Minimal._Pin):
        """Full GPIO proxy — adds watch()/unwatch() for polling-based input events."""

        def watch(self, handler, trigger=None):
            """Subscribe to this pin's edge events (polling-based).

            Args:
                handler: Callable(pin) invoked when the pin matches trigger.
                trigger: Pca9536Full.IRQ_RISING, .IRQ_FALLING, or None
                    (default) for either edge. The chip reports changes
                    only; the edge filter is applied in software.

            Raises:
                ValueError: if the pin is configured as an output.
            """
            self._chip._watch_pin(self._n, handler, trigger)

        def unwatch(self):
            """Unsubscribe this pin's handler."""
            self._chip._unwatch_pin(self._n)

    class _CPPin(Pca9536Minimal._CPPin):
        """Full CircuitPython pin — adds watch()/unwatch()."""

        def watch(self, handler, trigger=None):
            """Subscribe to this pin's edge events (see _Pin.watch)."""
            self._chip._watch_pin(self._n, handler, trigger)

        def unwatch(self):
            """Unsubscribe this pin's handler."""
            self._chip._unwatch_pin(self._n)
