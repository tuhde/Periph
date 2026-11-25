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


class Pca6408aMinimal:
    """PCA6408A 8-bit I/O expander (I²C / SMBus) — minimal interface.

    Provides 8 bidirectional GPIO pins (P0–P7) as GPIO objects via pin().
    CONFIG bit = 1 means input, 0 means output. The chip has no internal
    pull-ups. A shadow of OUTPUT and CONFIG is kept so a single-pin change is
    one register write.

    Args:
        connection: RegisterConnection (I²C or SMBus, no PEC) for the device.
        addr: 7-bit I²C device address (0x20 with ADDR = VSS, 0x21 with
            ADDR = VDD(P)); informational, the connection owns the address.
    """

    IN  = 0
    OUT = 1

    def __init__(self, connection, addr=0x20):
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
        self._last_input = self._reg_read(_REG_INPUT)

    def _write_output(self, mask):
        self._shadow = mask & 0xFF
        self._reg_write(_REG_OUTPUT, self._shadow)

    def _read_port_raw(self):
        return self._reg_read(_REG_INPUT)

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
        """Return a Pin proxy object for pin number n (0–7).

        Args:
            n: Pin index 0–7.
            mode: Optional direction to set immediately (IN or OUT).
                If omitted, the pin keeps its current CONFIG setting.

        Returns:
            _Pin (MicroPython/Linux) or _CPPin (CircuitPython).
        """
        if _CP:
            return self._CPPin(self, n)
        p = self._Pin(self, n)
        if mode is not None:
            p.init(mode)
        return p

    def read_port(self, port=0):
        """Read all 8 pins as a bitmask (INPUT register, actual pin levels).

        Args:
            port: Ignored; the chip has a single port.

        Returns:
            int: 8-bit bitmask; bit 0 = P0.
        """
        return self._read_port_raw()

    def write_port(self, port=0, mask=0):
        """Write all 8 output bits via OUTPUT and update the shadow register.

        Bits of input pins are stored but have no effect until the pin
        becomes an output.

        Args:
            port: Ignored; the chip has a single port.
            mask: 8-bit output mask; bit 0 = P0.
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
                pull: Must be None; the chip has no pull-up resistors.

            Raises:
                AttributeError: if a pull is requested.
            """
            if pull is not None:
                raise AttributeError('PCA6408A has no pull-up resistors')
            self._chip._set_direction(self._n, mode == Pca6408aMinimal.IN)

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
                raise AttributeError('PCA6408A has no pull-up or pull-down resistors')

        def switch_to_input(self, pull=None):
            self.pull = pull
            self.direction = _digitalio.Direction.INPUT

        def switch_to_output(self, value=False, drive_mode=None):
            if value:
                self._chip._set_pin(self._n, 1)
            self.direction = _digitalio.Direction.OUTPUT

        def deinit(self):
            pass


class Pca6408aFull(Pca6408aMinimal):
    """PCA6408A full interface — polarity, whole-port direction, interrupts, raw access.

    Args:
        connection: RegisterConnection (I²C or SMBus, no PEC) for the device.
        addr: 7-bit I²C device address (default 0x20).
    """

    IRQ_RISING  = 0x01
    IRQ_FALLING = 0x02

    def __init__(self, connection, addr=0x20):
        Pca6408aMinimal.__init__(self, connection, addr)
        self._callback = None
        self._poll_thread = None
        self._poll_stop = True
        self._pin_handlers = {}
        self._pin_dispatch = False
        self._int_source = None

    def configure_polarity(self, mask):
        """Set input polarity inversion (POLARITY ← mask).

        Args:
            mask: 8-bit mask; bit n = 1 inverts the INPUT bit of Pn.
        """
        self._polarity = mask & 0xFF
        self._reg_write(_REG_POLARITY, self._polarity)
        self._last_input = self._reg_read(_REG_INPUT)

    def get_direction(self):
        """Return the shadow CONFIG register (1 = input).

        Returns:
            int: 8-bit direction mask.
        """
        return self._direction

    def set_direction(self, mask):
        """Write CONFIG in one transaction (1 = input, 0 = output).

        For pins turning into outputs the OUTPUT bits are written first so
        the pins never glitch. The cached input value is refreshed afterwards
        to drop the false interrupt an output→input change can raise.

        Args:
            mask: 8-bit direction mask; bit n = 1 makes Pn an input.
        """
        self._reg_write(_REG_OUTPUT, self._shadow)
        self._direction = mask & 0xFF
        self._reg_write(_REG_CONFIG, self._direction)
        self._last_input = self._reg_read(_REG_INPUT)

    def read_register(self, reg):
        """Read a raw register (debug / escape hatch).

        Args:
            reg: Register address 0–3.

        Returns:
            int: Register value.
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

        OUTPUT ← 0xFF, POLARITY ← 0x00, CONFIG ← 0xFF. The RESET pin is
        external and not driven by the driver.
        """
        self.off_interrupt()
        self._init_chip()

    def on_interrupt(self, callback, int_pin=None):
        """Subscribe to INT (falling edge).

        ``callback(changed_mask)`` receives the bitmask of input pins whose
        level changed since the previous INPUT read. Delivery uses int_pin,
        else connection.int_pin, else a 5 ms polling thread (Linux).

        Args:
            callback: Callable(changed_mask: int).
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
        changed = self.poll_interrupt()
        if changed and self._callback:
            self._callback(changed)

    def _poll_loop(self):
        import time
        while not self._poll_stop:
            self._int_handler()
            time.sleep(0.005)

    def poll_interrupt(self):
        """Read INPUT (clearing INT) and return the changed-pin mask.

        The mask is the XOR of the new and previously cached INPUT value,
        limited to pins configured as inputs.

        Returns:
            int: 8-bit mask; bit n = 1 if input Pn changed.
        """
        new = self._reg_read(_REG_INPUT)
        changed = (new ^ self._last_input) & self._direction
        self._last_input = new
        self._last_level = new
        return changed

    def _watch_pin(self, n, handler, trigger):
        if (self._direction >> n) & 1 == 0:
            raise ValueError('cannot watch an output pin')
        self._pin_handlers[n] = (handler, trigger)
        if not self._pin_dispatch:
            self._pin_dispatch = True
            self.on_interrupt(self._dispatch_pins)

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

    class _Pin(Pca6408aMinimal._Pin):
        """Full GPIO proxy — adds watch()/unwatch() for interrupt-driven input."""

        def watch(self, handler, trigger=None):
            """Subscribe to this pin's edge events.

            Args:
                handler: Callable(pin) invoked when the pin matches trigger.
                trigger: Pca6408aFull.IRQ_RISING, .IRQ_FALLING, or None
                    (default) for either edge. The chip reports changes
                    only; the edge filter is applied in software.

            Raises:
                ValueError: if the pin is configured as an output.
            """
            self._chip._watch_pin(self._n, handler, trigger)

        def unwatch(self):
            """Unsubscribe this pin's handler."""
            self._chip._unwatch_pin(self._n)

    class _CPPin(Pca6408aMinimal._CPPin):
        """Full CircuitPython pin — adds watch()/unwatch()."""

        def watch(self, handler, trigger=None):
            """Subscribe to this pin's edge events (see _Pin.watch)."""
            self._chip._watch_pin(self._n, handler, trigger)

        def unwatch(self):
            """Unsubscribe this pin's handler."""
            self._chip._unwatch_pin(self._n)
