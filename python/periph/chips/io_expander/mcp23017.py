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


class Mcp23017Minimal:
    """MCP23017 16-bit I/O port expander — minimal interface.

    Provides 16 GPIO pins (GPA0–GPA7, GPB0–GPB7) as GPIO objects via pin().
    Direction is explicit: IODIR bit = 1 means input, 0 means output.
    Each pin can source/sink up to 25 mA.

    GPA7 and GPB7 are output-only per the MCP23017 datasheet — these pins
    must be initialised as outputs (IODIR bit cleared). The driver enforces
    this by setting IODIRA = IODIRB = 0x7F at init, making GPA0–GPA6 and
    GPB0–GPB6 inputs, GPA7/GPB7 outputs.

    A shadow register is maintained for OLATA/OLATB so individual output
    pins can be set/cleared/toggled without a read-modify-write transaction.

    Args:
        connection: Configured I²C connection pointing at the device.
        addr: 7-bit I²C device address (default 0x20, range 0x20–0x27).
    """

    IN  = 0
    OUT = 1

    _REG_IODIRA = 0x00
    _REG_IODIRB = 0x01
    _REG_IPOLA  = 0x02
    _REG_IPOLB  = 0x03
    _REG_GPPUA  = 0x0C
    _REG_GPPUB  = 0x0D
    _REG_GPIOA  = 0x12
    _REG_GPIOB  = 0x13
    _REG_OLATA  = 0x14
    _REG_OLATB  = 0x15

    def __init__(self, connection, addr=0x20):
        self._connection = connection
        self._addr = addr
        self._shadow = [0, 0]
        self._direction = [0x7F, 0x7F]
        self._write_reg(self._REG_OLATA, 0x00)
        self._write_reg(self._REG_OLATB, 0x00)
        self._write_reg(self._REG_IODIRA, 0x7F)
        self._write_reg(self._REG_IODIRB, 0x7F)
        self._write_reg(self._REG_IPOLA, 0x00)
        self._write_reg(self._REG_IPOLB, 0x00)
        self._write_reg(self._REG_GPPUA, 0x00)
        self._write_reg(self._REG_GPPUB, 0x00)

    def _write_reg(self, reg, value):
        self._connection.write(bytes([reg, value]))

    def _read_reg(self, reg, n=1):
        self._connection.write(bytes([reg]))
        return self._connection.read(n)

    def _write_port(self, port, mask):
        self._shadow[port & 1] = mask & 0xFF
        self._write_reg(self._REG_OLATA + (port & 1), mask)

    def _read_port_raw(self, port):
        return self._read_reg(self._REG_GPIOA + (port & 1), 1)[0]

    def _set_pin(self, n, value):
        port = n >> 3
        bit = n & 7
        if value:
            self._shadow[port] |= (1 << bit)
        else:
            self._shadow[port] &= ~(1 << bit)
        self._write_port(port, self._shadow[port])

    def pin(self, n, mode=None):
        """Return a Pin proxy object for pin number n (0–15).

        Pins 0–7 = PORTA (GPA0–GPA7), pins 8–15 = PORTB (GPB0–GPB7).
        GPA7 (pin 7) and GPB7 (pin 15) are output-only.

        Args:
            n: Pin index 0–15.
            mode: Optional direction to set immediately. Pass IN or OUT.
                  If omitted, the pin adopts the current IODIR setting.

        Returns:
            _Pin (MicroPython/Linux) or _CPPin (CircuitPython).
        """
        if _CP:
            return self._CPPin(self, n)
        p = self._Pin(self, n)
        if mode is not None:
            p.init(mode)
        return p

    def read_port(self, port):
        """Read all 8 pins of a port as a bitmask.

        Args:
            port: Port index, 0 = PORTA, 1 = PORTB.

        Returns:
            int: 8-bit bitmask; bit 0 = pin 0 (GPA0/GPB0).
        """
        return self._read_port_raw(port)

    def write_port(self, port, mask):
        """Write all 8 output pins of a port and update the shadow register.

        Args:
            port: Port index, 0 = PORTA, 1 = PORTB.
            mask: 8-bit output mask; bit 0 = pin 0.
        """
        self._write_port(port, mask)

    class _Pin:
        """GPIO proxy for a single MCP23017 pin — machine.Pin-compatible.

        Obtain via Mcp23017Minimal.pin(n). Do not instantiate directly.
        GPA7 (n=7) and GPB7 (n=15) are output-only.
        """

        IN  = 0
        OUT = 1

        def __init__(self, chip, n):
            self._chip = chip
            self._n = n
            self._port = n >> 3
            self._bit = n & 7

        def init(self, mode, pull=None):
            """Set pin direction.

            Args:
                mode: Mcp23017Minimal.IN (0) or OUT (1).
                pull: Ignored; per-pin pull-ups require Mcp23017Full.
            """
            port = self._port
            bit = self._bit
            if mode == Mcp23017Minimal.IN:
                dir_mask = self._chip._direction[port] | (1 << bit)
            else:
                dir_mask = self._chip._direction[port] & ~(1 << bit)
            self._chip._direction[port] = dir_mask
            self._chip._write_reg(self._chip._REG_IODIRA + port, dir_mask)

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
                return (self._chip._read_port_raw(self._port) >> self._bit) & 1
            self._chip._set_pin(self._n, x)

        def on(self):
            """Set pin high (output latch = 1)."""
            self._chip._set_pin(self._n, 1)

        def off(self):
            """Drive pin low (output latch = 0)."""
            self._chip._set_pin(self._n, 0)

        def toggle(self):
            """Invert the current output latch bit for this pin."""
            self._chip._set_pin(self._n, 1 - ((self._chip._shadow[self._port] >> self._bit) & 1))

    class _CPPin:
        """GPIO proxy for a single MCP23017 pin — digitalio.DigitalInOut-compatible.

        Obtain via Mcp23017Minimal.pin(n). Do not instantiate directly.
        """

        def __init__(self, chip, n):
            self._chip = chip
            self._n = n
            self._port = n >> 3
            self._bit = n & 7
            self._direction = _digitalio.Direction.INPUT

        @property
        def direction(self):
            return self._direction

        @direction.setter
        def direction(self, d):
            self._direction = d
            port = self._port
            bit = self._bit
            if d == _digitalio.Direction.INPUT:
                dir_mask = self._chip._direction[port] | (1 << bit)
            else:
                dir_mask = self._chip._direction[port] & ~(1 << bit)
            self._chip._direction[port] = dir_mask
            self._chip._write_reg(self._chip._REG_IODIRA + port, dir_mask)

        @property
        def value(self):
            return bool((self._chip._read_port_raw(self._port) >> self._bit) & 1)

        @value.setter
        def value(self, v):
            self._chip._set_pin(self._n, int(bool(v)))

        def switch_to_input(self, pull=None):
            self.direction = _digitalio.Direction.INPUT

        def switch_to_output(self, value=False, drive_mode=None):
            self.direction = _digitalio.Direction.OUTPUT
            if value:
                self._chip._set_pin(self._n, 1)

        def deinit(self):
            pass


class Mcp23017Full(Mcp23017Minimal):
    """MCP23017 full interface — extends minimal with pull-ups, polarity, and interrupts.

    Adds per-pin pull-up configuration (GPPU), optional INTA/INTB callbacks,
    interrupt-on-change mode, default-compare mode, and poll_interrupt().

    Args:
        connection: Configured I²C connection pointing at the device.
        addr: 7-bit I²C device address (default 0x20).
    """

    IRQ_RISING  = 0x01
    IRQ_FALLING = 0x02

    _REG_IPOLA   = 0x02
    _REG_IPOLB   = 0x03
    _REG_GPINTENA = 0x04
    _REG_GPINTENB = 0x05
    _REG_DEFVALA  = 0x06
    _REG_DEFVALB  = 0x07
    _REG_INTCONA  = 0x08
    _REG_INTCONB  = 0x09
    _REG_IOCON    = 0x0A
    _REG_INTFA    = 0x0E
    _REG_INTFB    = 0x0F
    _REG_INTCAPA  = 0x10
    _REG_INTCAPB  = 0x11

    def __init__(self, connection, addr=0x20):
        self._connection = connection
        self._addr = addr
        self._shadow = [0, 0]
        self._direction = [0x7F, 0x7F]
        self._pullup = [0, 0]
        self._write_reg(self._REG_OLATA, 0x00)
        self._write_reg(self._REG_OLATB, 0x00)
        self._write_reg(self._REG_IODIRA, 0x7F)
        self._write_reg(self._REG_IODIRB, 0x7F)
        self._write_reg(self._REG_IPOLA, 0x00)
        self._write_reg(self._REG_IPOLB, 0x00)
        self._write_reg(self._REG_GPPUA, 0x00)
        self._write_reg(self._REG_GPPUB, 0x00)
        self._callback = [None, None]
        self._poll_thread = [None, None]
        self._poll_stop = [False, False]

    def configure_pullup(self, port, mask):
        """Enable/disable per-pin 100 kΩ pull-ups on a port.

        Pull-ups are only electrically effective on pins configured as inputs.
        The driver does not enforce this — the hardware handles it.

        Args:
            port: Port index, 0 = PORTA, 1 = PORTB.
            mask: 8-bit mask; bit n = 1 enables pull-up on pin n.
        """
        self._pullup[port & 1] = mask & 0xFF
        self._write_reg(self._REG_GPPUA + (port & 1), mask)

    def configure_polarity(self, port, mask):
        """Set input polarity inversion per pin.

        Args:
            port: Port index, 0 = PORTA, 1 = PORTB.
            mask: 8-bit mask; bit n = 1 inverts GPIO read for pin n.
        """
        self._write_reg(self._REG_IPOLA + (port & 1), mask)

    def on_interrupt(self, callback, port=None, int_pin=None, mode='change', mirror=False):
        """Subscribe to INT assertions.

        Level 3: the chip has two independent INT lines (INTA/INTB), one per
        port. Subscribing to both ports at once (port=None) is the common
        case; callback then receives (port: int, status: int). Subscribing a
        single port passes callback(status: int) instead.

        Delivery: if int_pin is given, it is wired directly (useful when INTA
        and INTB are wired to two separate GPIOs). Otherwise falls back to
        connection.int_pin (typical when IOCON.MIRROR ties both lines
        together into one physical GPIO), or a 5 ms polling thread on Linux
        if neither is available.

        Args:
            callback: Callable(port, status) if port=None, else Callable(status).
            port: None (default) to subscribe both ports; 0 or 1 for one port.
            int_pin: Optional InputPin for this call, overriding connection.int_pin.
            mode: 'change' compares against previous pin value;
                  'default' compares against DEFVAL register.
            mirror: If True, sets IOCON.MIRROR so either port's interrupt
                    activates both INTA and INTB.
        """
        ports = [0, 1] if port is None else [port & 1]
        for p in ports:
            self._callback[p] = (lambda status, p=p: callback(p, status)) if port is None else callback
            intcon_val = 0 if mode == 'change' else 0xFF
            self._write_reg(self._REG_INTCONA + p, intcon_val)
            self._write_reg(self._REG_GPINTENA + p, 0xFF)

        iocon = self._read_reg(self._REG_IOCON, 1)[0]
        if mirror:
            iocon |= (1 << 6)
        self._write_reg(self._REG_IOCON, iocon)

        pin = int_pin if int_pin is not None else self._connection.int_pin
        for p in ports:
            if pin is not None:
                from periph.connection.input_pin import InputPin
                pin.on_edge(lambda p=p: self._int_handler(p), InputPin.FALLING)
            elif _LINUX:
                self._poll_stop[p] = False
                self._poll_thread[p] = _threading.Thread(
                    target=self._poll_loop, args=(p,), daemon=True)
                self._poll_thread[p].start()

    def off_interrupt(self, port=None):
        """Unsubscribe and stop delivery.

        Args:
            port: None (default) to unsubscribe both ports; 0 or 1 for one port.
        """
        ports = [0, 1] if port is None else [port & 1]
        pin = self._connection.int_pin
        for p in ports:
            self._write_reg(self._REG_GPINTENA + p, 0x00)
            if pin is None:
                self._poll_stop[p] = True
            self._callback[p] = None

    def _int_handler(self, port):
        status = self.poll_interrupt(port)
        if status and self._callback[port]:
            self._callback[port](status)

    def set_default_value(self, port, mask):
        """Set DEFVAL register for default-compare interrupt mode.

        Args:
            port: Port index, 0 = PORTA, 1 = PORTB.
            mask: 8-bit default compare value.
        """
        self._write_reg(self._REG_DEFVALA + (port & 1), mask)

    def poll_interrupt(self, port):
        """Read & clear interrupt status; returns the raw INTF flag register.

        Reads INTFA/INTFB (which pins triggered) then INTCAPA/INTCAPB (which
        clears the interrupt latch and re-arms it). Note that reading either
        INTCAP or GPIO clears the interrupt condition on this chip — call
        poll_interrupt() or read_capture() per event, not both.

        Args:
            port: Port index, 0 = PORTA, 1 = PORTB.

        Returns:
            int: 8-bit interrupt flag mask; bit n = 1 if pin n triggered.
        """
        flags = self._read_reg(self._REG_INTFA + (port & 1), 1)[0]
        self._read_reg(self._REG_INTCAPA + (port & 1), 1)
        return flags

    def read_capture(self, port):
        """Read INTCAP: the port state latched at the moment of the interrupt.

        Also clears the interrupt latch (see poll_interrupt() docstring) —
        call this instead of poll_interrupt() when the captured pin state is
        wanted rather than the flag register.

        Args:
            port: Port index, 0 = PORTA, 1 = PORTB.

        Returns:
            int: 8-bit captured port bitmask at the moment of interrupt.
        """
        return self._read_reg(self._REG_INTCAPA + (port & 1), 1)[0]

    def _poll_loop(self, port):
        prev = self._read_port_raw(port)
        while not self._poll_stop[port]:
            curr = self._read_port_raw(port)
            changed = curr ^ prev
            if changed and self._callback[port]:
                self._callback[port](changed)
            prev = curr
            import time
            time.sleep(0.005)

    class _Pin(Mcp23017Minimal._Pin):
        """Full GPIO proxy — adds watch()/unwatch() for interrupt-driven input."""

        def watch(self, handler, trigger=None):
            """Subscribe to this pin's edge events.

            Args:
                handler: Callable(pin) invoked when the pin matches trigger.
                trigger: Mcp23017Full.IRQ_RISING, .IRQ_FALLING, or None
                    (default) for either edge (CHANGE).
            """
            n = self._n
            bit = self._bit
            chip = self._chip
            trigger_val = None if trigger is None else (1 if trigger == Mcp23017Full.IRQ_RISING else 0)

            def _wrap(status):
                if (status >> bit) & 1:
                    current = (chip._read_port_raw(self._port) >> bit) & 1
                    if trigger_val is None or current == trigger_val:
                        handler(self)

            chip._pin_handlers = getattr(chip, '_pin_handlers', [{}, {}])
            chip._pin_handlers[self._port][n] = _wrap
            self._install_dispatch(chip, self._port)

        def unwatch(self):
            """Unsubscribe this pin's handler. No-op if not registered."""
            handlers = getattr(self._chip, '_pin_handlers', [{}, {}])
            handlers[self._port].pop(self._n, None)

        @staticmethod
        def _install_dispatch(chip, port):
            """Wire chip._callback[port] to fan out to every registered pin handler.

            Idempotent: only wraps once per port regardless of how many pins
            call watch(), avoiding the double-dispatch that re-wrapping on
            every call would otherwise cause.
            """
            installed = getattr(chip, '_pin_dispatch_installed', [False, False])
            chip._pin_dispatch_installed = installed
            if installed[port]:
                return
            installed[port] = True
            orig_cb = chip._callback[port]

            def _combined(status):
                if orig_cb:
                    orig_cb(status)
                for fn in list(chip._pin_handlers[port].values()):
                    fn(status)

            chip._callback[port] = _combined

    class _CPPin(Mcp23017Minimal._CPPin):
        """Full CircuitPython pin — supports pull-up."""

        @property
        def pull(self):
            port = self._port
            bit = self._bit
            return _digitalio.Pull.UP if (self._chip._pullup[port] >> bit) & 1 else None

        @pull.setter
        def pull(self, v):
            port = self._port
            bit = self._bit
            cur = self._chip._pullup[port]
            if v == _digitalio.Pull.UP:
                cur |= (1 << bit)
            else:
                cur &= ~(1 << bit)
            self._chip._pullup[port] = cur
            self._chip._write_reg(self._chip._REG_GPPUA + port, cur)