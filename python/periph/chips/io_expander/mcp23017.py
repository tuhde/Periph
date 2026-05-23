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
        transport: Configured I²C transport pointing at the device.
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

    def __init__(self, transport, addr=0x20):
        self._transport = transport
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
        self._transport.write(bytes([reg, value]))

    def _read_reg(self, reg, n=1):
        self._transport.write(bytes([reg]))
        return self._transport.read(n)

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
    interrupt-on-change mode, default-compare mode, and clear_interrupt().

    Args:
        transport: Configured I²C transport pointing at the device.
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

    def __init__(self, transport, addr=0x20):
        self._transport = transport
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
        self._callback = None
        self._int_pin = None
        self._poll_thread = None
        self._poll_stop = False

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

    def configure_interrupt(self, port, int_pin, callback, mode='change', mirror=False):
        """Enable interrupt for a port.

        Args:
            port: Port index, 0 = PORTA, 1 = PORTB.
            int_pin: Hardware GPIO pin connected to the chip's INT line,
                     or None to use a 5 ms polling thread (Linux only).
            callback: Callable(changed_mask: int) called with 8-bit bitmask
                      of pins that changed when an interrupt fires.
            mode: 'change' compares against previous pin value;
                  'default' compares against DEFVAL register.
            mirror: If True, sets IOCON.MIRROR so either port's interrupt
                    activates both INTA and INTB.
        """
        self._callback = callback
        self._int_pin = int_pin
        intcon_reg = self._REG_INTCONA + (port & 1)
        intcon_val = 0 if mode == 'change' else 0xFF
        self._write_reg(intcon_reg, intcon_val)
        self._write_reg(self._REG_GPINTENA + (port & 1), 0xFF)
        iocon = self._read_reg(self._REG_IOCON, 1)[0]
        if mirror:
            iocon |= (1 << 6)
        self._write_reg(self._REG_IOCON, iocon)
        if int_pin is None and _LINUX:
            self._poll_stop = False
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()

    def set_default_value(self, port, mask):
        """Set DEFVAL register for default-compare interrupt mode.

        Args:
            port: Port index, 0 = PORTA, 1 = PORTB.
            mask: 8-bit default compare value.
        """
        self._write_reg(self._REG_DEFVALA + (port & 1), mask)

    def clear_interrupt(self, port):
        """Read INTCAP and return captured port state; clears INT for the port.

        Args:
            port: Port index, 0 = PORTA, 1 = PORTB.

        Returns:
            int: 8-bit captured port bitmask at the moment of interrupt.
        """
        return self._read_reg(self._REG_INTCAPA + (port & 1), 1)[0]

    def read_interrupt_flags(self, port):
        """Read INTFA/INTFB without clearing the interrupt.

        Args:
            port: Port index, 0 = PORTA, 1 = PORTB.

        Returns:
            int: 8-bit interrupt flag mask.
        """
        return self._read_reg(self._REG_INTFA + (port & 1), 1)[0]

    def stop_interrupt(self, port):
        """Disable interrupt for the port.

        Args:
            port: Port index, 0 = PORTA, 1 = PORTB.
        """
        self._write_reg(self._REG_GPINTENA + (port & 1), 0x00)
        if self._poll_stop is False:
            self._poll_stop = True

    def _poll_loop(self):
        prev = [self._read_port_raw(0), self._read_port_raw(1)]
        while not self._poll_stop:
            curr0 = self._read_port_raw(0)
            curr1 = self._read_port_raw(1)
            changed0 = curr0 ^ prev[0]
            changed1 = curr1 ^ prev[1]
            if changed0 and self._callback:
                self._callback(changed0)
            if changed1 and self._callback:
                self._callback(changed1)
            prev = [curr0, curr1]
            import time
            time.sleep(0.005)

    class _Pin(Mcp23017Minimal._Pin):
        """Full GPIO proxy — adds irq() for interrupt-driven input."""

        def irq(self, handler, trigger):
            """Register an interrupt handler for this pin.

            Args:
                handler: Callable(pin) invoked when the pin matches trigger.
                trigger: Mcp23017Full.IRQ_RISING or IRQ_FALLING.
            """
            n = self._n
            chip = self._chip
            trigger_val = 1 if trigger == Mcp23017Full.IRQ_RISING else 0

            def _wrap(changed_mask):
                if (changed_mask >> n) & 1:
                    current = (chip._read_port_raw(self._port) >> n) & 1
                    if current == trigger_val:
                        handler(self)

            chip._pin_handlers = getattr(chip, '_pin_handlers', {})
            chip._pin_handlers[n] = _wrap
            orig_cb = chip._callback

            def _combined(changed_mask):
                if orig_cb:
                    orig_cb(changed_mask)
                for fn in chip._pin_handlers.values():
                    fn(changed_mask)

            chip._callback = _combined

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