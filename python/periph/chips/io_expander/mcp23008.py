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

_REG_IODIR = 0x00
_REG_IPOL = 0x01
_REG_GPINTEN = 0x02
_REG_DEFVAL = 0x03
_REG_INTCON = 0x04
_REG_IOCON = 0x05
_REG_GPPU = 0x06
_REG_INTF = 0x07
_REG_INTCAP = 0x08
_REG_GPIO = 0x09
_REG_OLAT = 0x0A

_IOCON_SEQOP = 0x20
_IOCON_HAEN = 0x08
_IOCON_ODR = 0x04
_IOCON_INTPOL = 0x02


class _Mcp23x08Base:
    """Shared logic of the MCP23008 (I²C) and MCP23S08 (SPI) drivers.

    Holds the OLAT / IODIR shadows and the pin proxies. Subclasses supply the
    two bus hooks ``_reg_read(reg, n)`` and ``_reg_write(reg, data)``; the two
    chips have an identical register map and GPIO behaviour.
    """

    IN  = 0
    OUT = 1

    _IOCON_BASE = 0x00

    def _init_chip(self):
        self._shadow = 0x00
        self._direction = 0xFF
        self._iocon = self._IOCON_BASE
        self._reg_write(_REG_OLAT, 0x00)

    def _write_olat(self, mask):
        self._shadow = mask & 0xFF
        self._reg_write(_REG_OLAT, self._shadow)

    def _read_port_raw(self):
        return self._reg_read(_REG_GPIO, 1)[0]

    def _set_pin(self, n, value):
        if value:
            self._shadow |= (1 << n)
        else:
            self._shadow &= ~(1 << n)
        self._write_olat(self._shadow)

    def _set_direction(self, n, is_input):
        # Latch the output level first (shadow already holds it) so the pin
        # never glitches to a stale level when it becomes an output.
        if is_input:
            self._direction |= (1 << n)
        else:
            self._reg_write(_REG_OLAT, self._shadow)
            self._direction &= ~(1 << n)
        self._reg_write(_REG_IODIR, self._direction)

    def pin(self, n, mode=None):
        """Return a Pin proxy object for pin number n (0–7).

        All eight pins (GP0–GP7) are bidirectional.

        Args:
            n: Pin index 0–7.
            mode: Optional direction to set immediately (IN or OUT).
                  If omitted, the pin keeps its current IODIR setting.

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
        """Read all 8 pins as a bitmask (GPIO register, actual pin levels).

        Args:
            port: Ignored; the chip has a single port.

        Returns:
            int: 8-bit bitmask; bit 0 = GP0.
        """
        return self._read_port_raw()

    def write_port(self, port=0, mask=0):
        """Write all 8 output bits via OLAT and update the shadow register.

        Bits of input pins are stored but have no effect until the pin
        becomes an output.

        Args:
            port: Ignored; the chip has a single port.
            mask: 8-bit output mask; bit 0 = GP0.
        """
        self._write_olat(mask)

    class _Pin:
        """GPIO proxy for a single pin — machine.Pin-compatible.

        Obtain via ``pin(n)``. Do not instantiate directly.
        """

        IN  = 0
        OUT = 1

        def __init__(self, chip, n):
            self._chip = chip
            self._n = n
            self._bit = n & 7

        def init(self, mode, pull=None):
            """Set pin direction.

            Args:
                mode: IN (0) or OUT (1).
                pull: Ignored; per-pin pull-ups require the Full driver.
            """
            self._chip._set_direction(self._n, mode == _Mcp23x08Base.IN)

        def value(self, x=None):
            """Read or write the pin.

            With no argument, returns the actual logic level at the pin.
            With an argument, sets the output latch.

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

        def switch_to_input(self, pull=None):
            self.direction = _digitalio.Direction.INPUT

        def switch_to_output(self, value=False, drive_mode=None):
            if value:
                self._chip._set_pin(self._n, 1)
            self.direction = _digitalio.Direction.OUTPUT

        def deinit(self):
            pass


class Mcp23008Minimal(_Mcp23x08Base):
    """MCP23008 8-bit I/O port expander (I²C) — minimal interface.

    Provides 8 bidirectional GPIO pins (GP0–GP7) as GPIO objects via pin().
    Direction is explicit: IODIR bit = 1 means input, 0 means output. Each
    pin can source/sink up to 25 mA. A shadow of OLAT and IODIR is kept so a
    single-pin change is one register write.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
        addr: 7-bit I²C device address (default 0x20, range 0x20–0x27).
    """

    def __init__(self, connection, addr=0x20):
        self._connection = connection
        self._addr = addr
        self._init_chip()

    def _reg_read(self, reg, n):
        return self._connection.read_reg(reg, n)

    def _reg_write(self, reg, data):
        self._connection.write_reg(reg, data)


class Mcp23S08Minimal(_Mcp23x08Base):
    """MCP23S08 8-bit I/O port expander (SPI) — minimal interface.

    Identical GPIO behaviour to Mcp23008Minimal. Every SPI transaction is
    ``[0x40 | (addr << 1) | R/W] [reg] [data…]``; the driver builds the two
    header bytes itself and talks to a plain Connection. ``IOCON.HAEN`` is set
    during init so up to four devices can share one chip select.

    Args:
        connection: SPI Connection (mode 0, MSB first, CS active low).
        addr: Hardware device address 0–3 (A1:A0 pins).
    """

    _IOCON_BASE = _IOCON_HAEN

    def __init__(self, connection, addr=0):
        self._connection = connection
        self._addr = addr & 3
        # HAEN resets to 0, so an un-addressed device decodes address 00:
        # send the first IOCON write with the address-0 opcode (also reaches
        # every device on a shared CS line).
        self._connection.write(bytes([0x40, _REG_IOCON, _IOCON_HAEN]))
        self._init_chip()

    def _reg_read(self, reg, n):
        return self._connection.write_read(bytes([0x40 | (self._addr << 1) | 1, reg]), n)

    def _reg_write(self, reg, data):
        if isinstance(data, int):
            data = bytes([data])
        self._connection.write(bytes([0x40 | (self._addr << 1), reg]) + bytes(data))


class _FullMixin:
    """Full-stage features shared by Mcp23008Full and Mcp23S08Full."""

    IRQ_RISING  = 0x01
    IRQ_FALLING = 0x02

    def _init_full(self):
        self._pullup = 0x00
        self._gpinten = 0x00
        self._int_active_high = False
        self._callback = None
        self._poll_thread = None
        self._poll_stop = True
        self._pin_handlers = {}
        self._pin_dispatch = False
        self._int_source = None

    def configure_pullup(self, mask):
        """Enable/disable per-pin 100 kΩ pull-ups (GPPU ← mask).

        Only effective on pins configured as inputs; the chip ignores the
        bit on outputs.

        Args:
            mask: 8-bit mask; bit n = 1 enables the pull-up on GPn.
        """
        self._pullup = mask & 0xFF
        self._reg_write(_REG_GPPU, self._pullup)

    def configure_polarity(self, mask):
        """Set input polarity inversion (IPOL ← mask).

        Args:
            mask: 8-bit mask; bit n = 1 inverts the GPIO read of GPn.
        """
        self._reg_write(_REG_IPOL, mask & 0xFF)

    def set_default_value(self, mask):
        """Set DEFVAL for default-compare interrupts.

        Args:
            mask: 8-bit default compare value.
        """
        self._reg_write(_REG_DEFVAL, mask & 0xFF)

    def enable_pin_interrupt(self, mask):
        """Arm interrupt-on-change pins (GPINTEN ← mask).

        Args:
            mask: 8-bit mask; bit n = 1 arms GPn (only inputs can interrupt).
        """
        self._gpinten = mask & 0xFF
        self._reg_write(_REG_GPINTEN, self._gpinten)

    def configure_int_pin(self, open_drain=False, active_high=False):
        """Configure the INT output (IOCON ODR / INTPOL).

        Args:
            open_drain: True for an open-drain INT (overrides active_high).
            active_high: True for active-high when push-pull.
        """
        self._int_active_high = bool(active_high) and not open_drain
        iocon = self._iocon & ~(_IOCON_ODR | _IOCON_INTPOL)
        if open_drain:
            iocon |= _IOCON_ODR
        elif active_high:
            iocon |= _IOCON_INTPOL
        self._iocon = iocon
        self._reg_write(_REG_IOCON, iocon)

    def set_sequential(self, enabled):
        """Enable or disable sequential register access (IOCON SEQOP).

        The driver itself never relies on a value other than enabled.

        Args:
            enabled: True (default, auto-increment) or False.
        """
        iocon = self._iocon & ~_IOCON_SEQOP
        if not enabled:
            iocon |= _IOCON_SEQOP
        self._iocon = iocon
        self._reg_write(_REG_IOCON, iocon)

    def read_register(self, reg):
        """Read a raw register (debug / escape hatch).

        Args:
            reg: Register address 0x00–0x0A.

        Returns:
            int: Register value.
        """
        return self._reg_read(reg, 1)[0]

    def write_register(self, reg, value):
        """Write a raw register; IODIR / OLAT / IOCON shadows are kept in sync.

        Args:
            reg: Register address 0x00–0x0A.
            value: 8-bit value.
        """
        value &= 0xFF
        if reg == _REG_IODIR:
            self._direction = value
        elif reg == _REG_OLAT:
            self._shadow = value
        elif reg == _REG_IOCON:
            self._iocon = value
        elif reg == _REG_GPPU:
            self._pullup = value
        elif reg == _REG_GPINTEN:
            self._gpinten = value
        self._reg_write(reg, value)

    def reset(self):
        """Rewrite every register to its reset value and clear the shadows.

        The chip has no reset command (RESET is an external pin). On the
        MCP23S08 IOCON is rewritten to HAEN (not 0) so addressed access keeps
        working.
        """
        self.off_interrupt()
        for reg in (_REG_IPOL, _REG_GPINTEN, _REG_DEFVAL, _REG_INTCON,
                    _REG_GPPU, _REG_OLAT):
            self._reg_write(reg, 0x00)
        self._reg_write(_REG_IODIR, 0xFF)
        self._iocon = self._IOCON_BASE
        self._reg_write(_REG_IOCON, self._iocon)
        self._shadow = 0x00
        self._direction = 0xFF
        self._pullup = 0x00
        self._gpinten = 0x00
        self._int_active_high = False

    def on_interrupt(self, callback, int_pin=None, mode='change'):
        """Subscribe to INT assertions.

        ``callback(status)`` receives the INTF bitmask of the pins that
        caused the interrupt. Every pin armed via enable_pin_interrupt() (or
        watch()) is used. Delivery uses int_pin, else connection.int_pin,
        else a 5 ms polling thread (Linux).

        Args:
            callback: Callable(status: int).
            int_pin: Optional InputPin overriding connection.int_pin.
            mode: 'change' compares against the previous pin value (INTCON=0);
                  'default' compares against DEFVAL (INTCON=1).

        Raises:
            ValueError: if no pin has been armed.
        """
        if not self._gpinten:
            raise ValueError('no pins armed: call enable_pin_interrupt() first')
        self._callback = callback
        self._reg_write(_REG_INTCON, self._gpinten if mode != 'change' else 0x00)
        pin = int_pin if int_pin is not None else getattr(self._connection, 'int_pin', None)
        if pin is not None:
            from periph.connection.input_pin import InputPin
            trigger = InputPin.RISING if self._int_active_high else InputPin.FALLING
            pin.on_edge(self._int_handler, trigger)
            self._int_source = pin
        elif _LINUX:
            self._poll_stop = False
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()

    def off_interrupt(self):
        """Unsubscribe and stop delivery (GPINTEN is left untouched)."""
        self._poll_stop = True
        src = getattr(self, '_int_source', None)
        if src is not None:
            src.off_edge(self._int_handler)
            self._int_source = None
        self._callback = None

    def _int_handler(self):
        status = self.poll_interrupt()
        if status and self._callback:
            self._callback(status)

    def _poll_loop(self):
        import time
        while not self._poll_stop:
            self._int_handler()
            time.sleep(0.005)

    def poll_interrupt(self):
        """Read INTF, then INTCAP (clearing the interrupt).

        The captured port value is discarded — use read_capture() to get it.

        Returns:
            int: INTF bitmask; bit n = 1 if GPn caused the interrupt.
        """
        flags = self._reg_read(_REG_INTF, 1)[0]
        self._reg_read(_REG_INTCAP, 1)
        return flags

    def read_capture(self):
        """Read INTCAP: the port state captured at the interrupt.

        Also clears the interrupt.

        Returns:
            int: 8-bit captured port value.
        """
        return self._reg_read(_REG_INTCAP, 1)[0]

    def _watch_pin(self, n, handler, trigger):
        if (self._direction >> n) & 1 == 0:
            raise ValueError('cannot watch an output pin')
        self._pin_handlers[n] = (handler, trigger)
        self._gpinten |= (1 << n)
        self._reg_write(_REG_GPINTEN, self._gpinten)
        if not self._pin_dispatch:
            self._pin_dispatch = True
            self.on_interrupt(self._dispatch_pins)

    def _unwatch_pin(self, n):
        self._pin_handlers.pop(n, None)
        self._gpinten &= ~(1 << n)
        self._reg_write(_REG_GPINTEN, self._gpinten)

    def _dispatch_pins(self, status):
        level = self.read_capture()
        for n, (handler, trigger) in list(self._pin_handlers.items()):
            if not (status >> n) & 1:
                continue
            current = (level >> n) & 1
            if trigger is None or current == (1 if trigger == self.IRQ_RISING else 0):
                handler(self._pin_obj(n))

    def _pin_obj(self, n):
        return self.pin(n)

    class _Pin(_Mcp23x08Base._Pin):
        """Full GPIO proxy — adds watch()/unwatch() for interrupt-driven input."""

        def watch(self, handler, trigger=None):
            """Subscribe to this pin's edge events (enables GPINTEN for the pin).

            Args:
                handler: Callable(pin) invoked when the pin matches trigger.
                trigger: Mcp23008Full.IRQ_RISING, .IRQ_FALLING, or None
                    (default) for either edge. The chip reports changes
                    only; the edge filter is applied in software.

            Raises:
                ValueError: if the pin is configured as an output.
            """
            self._chip._watch_pin(self._n, handler, trigger)

        def unwatch(self):
            """Unsubscribe this pin's handler and clear its GPINTEN bit."""
            self._chip._unwatch_pin(self._n)

    class _CPPin(_Mcp23x08Base._CPPin):
        """Full CircuitPython pin — supports pull-up and watch()."""

        @property
        def pull(self):
            return _digitalio.Pull.UP if (self._chip._pullup >> self._n) & 1 else None

        @pull.setter
        def pull(self, v):
            if v == _digitalio.Pull.DOWN:
                raise AttributeError('pull-down is not supported')
            cur = self._chip._pullup
            if v == _digitalio.Pull.UP:
                cur |= (1 << self._n)
            else:
                cur &= ~(1 << self._n)
            self._chip.configure_pullup(cur)

        def watch(self, handler, trigger=None):
            """Subscribe to this pin's edge events (see _Pin.watch)."""
            self._chip._watch_pin(self._n, handler, trigger)

        def unwatch(self):
            """Unsubscribe this pin's handler."""
            self._chip._unwatch_pin(self._n)


class Mcp23008Full(_FullMixin, Mcp23008Minimal):
    """MCP23008 full interface — pull-ups, polarity, interrupts, raw access.

    Args:
        connection: RegisterConnection (I²C or SMBus) pointing at the device.
        addr: 7-bit I²C device address (default 0x20).
    """

    def __init__(self, connection, addr=0x20):
        Mcp23008Minimal.__init__(self, connection, addr)
        self._init_full()


class Mcp23S08Full(_FullMixin, Mcp23S08Minimal):
    """MCP23S08 full interface — pull-ups, polarity, interrupts, raw access.

    Args:
        connection: SPI Connection (mode 0, MSB first, CS active low).
        addr: Hardware device address 0–3 (A1:A0 pins).
    """

    def __init__(self, connection, addr=0):
        Mcp23S08Minimal.__init__(self, connection, addr)
        self._init_full()
