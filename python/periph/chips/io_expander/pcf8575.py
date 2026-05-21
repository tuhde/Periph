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


class Pcf8575Minimal:
    """PCF8575 16-bit quasi-bidirectional I/O port expander — minimal interface.

    Exposes all 16 pins (P00–P07, P10–P17) as GPIO objects via the pin()
    factory. Direction is implicit: writing 1 puts a pin in input mode (weak
    pull-up); writing 0 drives it low. Two shadow registers track the output
    latches so individual bits can be set without a read-modify-write.

    Initialises all pins to input mode (shadow = [0xFF, 0xFF]) at construction.

    Args:
        transport: Configured I²C transport pointing at the device.
        addr: 7-bit I²C device address (default 0x20).
    """

    IN  = 0
    OUT = 1

    def __init__(self, transport, addr=0x20):
        self._transport = transport
        self._addr = addr
        self._shadow = [0xFF, 0xFF]
        self._write_port(0, 0xFF)
        self._write_port(1, 0xFF)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _write_port(self, port, mask):
        self._transport.write(bytes([mask]))

    def _write_both(self):
        self._transport.write(bytes(self._shadow))

    def _read_port_raw(self, port):
        raw = self._transport.read(2)
        return raw[port]

    def _set_pin(self, n, value):
        port_idx = n // 8
        bit = n % 8
        if value:
            self._shadow[port_idx] |= (1 << bit)
        else:
            self._shadow[port_idx] &= ~(1 << bit)
        self._write_both()

    # ------------------------------------------------------------------
    # Public driver API
    # ------------------------------------------------------------------

    def pin(self, n):
        """Return a Pin proxy object for pin number n (0–15).

        Args:
            n: Pin index, 0 (P00) to 15 (P17).

        Returns:
            _CPPin compatible with digitalio.DigitalInOut on CircuitPython,
            _Pin compatible with machine.Pin on MicroPython and Linux.
        """
        if _CP:
            return self._CPPin(self, n)
        return self._Pin(self, n)

    def read_port(self, port=0):
        """Read the 8 pins of the given port as a bitmask.

        Always performs a full 2-byte I²C read and returns the byte for
        the requested port. Bit 0 = pin 0 of the port, bit 7 = pin 7.

        Args:
            port: Port index (0 = P00–P07, 1 = P10–P17).

        Returns:
            int: 8-bit bitmask of current pin states.
        """
        return self._read_port_raw(port)

    def write_port(self, port=0, mask=0xFF):
        """Write all 8 pins of the given port at once.

        Preserves the other port's shadow value; updates the shadow register
        for the targeted port.

        Args:
            port: Port index (0 = P00–P07, 1 = P10–P17).
            mask: 8-bit output mask. Bit 0 = pin 0 of port, bit 7 = pin 7.
                  1 = input mode (weak pull-up); 0 = drive low.
        """
        self._shadow[port] = mask & 0xFF
        self._write_both()

    # ------------------------------------------------------------------
    # MicroPython / Linux Pin proxy
    # ------------------------------------------------------------------

    class _Pin:
        """GPIO proxy for a single PCF8575 pin — machine.Pin-compatible interface.

        Obtain via Pcf8575Minimal.pin(n). Do not instantiate directly.

        Args:
            chip: Parent Pcf8575Minimal instance.
            n: Pin index (0–15).
        """

        def __init__(self, chip, n):
            self._chip = chip
            self._n = n

        def init(self, mode, pull=None):
            """Set pin direction.

            Args:
                mode: Pcf8575Minimal.IN (0) or Pcf8575Minimal.OUT (1).
                pull: Ignored; the PCF8575 has a fixed internal pull-up
                      when in input mode.
            """
            if mode == Pcf8575Minimal.IN:
                self._chip._set_pin(self._n, 1)
            else:
                self._chip._set_pin(self._n, 0)

        def value(self, x=None):
            """Read or write the pin.

            With no argument, returns the actual logic level at the pin.
            With an argument, sets the output latch (1 = input/quasi-high,
            0 = drive low).

            Args:
                x: None to read; 0 or 1 to write.

            Returns:
                int: Logic level (0 or 1) when reading; None when writing.
            """
            if x is None:
                port = self._n // 8
                return (self._chip._read_port_raw(port) >> (self._n % 8)) & 1
            self._chip._set_pin(self._n, x)

        def on(self):
            """Set pin high (release to input/quasi-high mode)."""
            self._chip._set_pin(self._n, 1)

        def off(self):
            """Drive pin low."""
            self._chip._set_pin(self._n, 0)

        def toggle(self):
            """Invert the current shadow bit for this pin."""
            port_idx = self._n // 8
            bit = self._n % 8
            cur = (self._chip._shadow[port_idx] >> bit) & 1
            self._chip._set_pin(self._n, 1 - cur)

    # ------------------------------------------------------------------
    # CircuitPython Pin proxy
    # ------------------------------------------------------------------

    class _CPPin:
        """GPIO proxy for a single PCF8575 pin — digitalio.DigitalInOut-compatible.

        Obtain via Pcf8575Minimal.pin(n). Do not instantiate directly.

        Args:
            chip: Parent Pcf8575Minimal instance.
            n: Pin index (0–15).
        """

        def __init__(self, chip, n):
            self._chip = chip
            self._n = n
            self._direction = _digitalio.Direction.INPUT

        @property
        def direction(self):
            """digitalio.Direction: Current pin direction."""
            return self._direction

        @direction.setter
        def direction(self, d):
            self._direction = d
            port_idx = self._n // 8
            bit = self._n % 8
            self._chip._shadow[port_idx] |= (1 << bit)
            self._chip._write_both()

        @property
        def value(self):
            """bool: Actual logic level at the pin."""
            port = self._n // 8
            return bool((self._chip._read_port_raw(port) >> (self._n % 8)) & 1)

        @value.setter
        def value(self, v):
            self._chip._set_pin(self._n, int(bool(v)))

        def switch_to_input(self, pull=None):
            """Configure pin as input.

            Args:
                pull: Ignored; the PCF8575 has a fixed internal pull-up.
            """
            self.direction = _digitalio.Direction.INPUT

        def switch_to_output(self, value=False, drive_mode=None):
            """Configure pin as output and set initial level.

            Args:
                value: Initial output level (default False = low).
                drive_mode: Ignored; the PCF8575 is always open-drain.
            """
            self._direction = _digitalio.Direction.OUTPUT
            self._chip._set_pin(self._n, int(value))

        def deinit(self):
            """Release the pin (no-op; shadow state is preserved)."""


class Pcf8575Full(Pcf8575Minimal):
    """PCF8575 full interface — extends Pcf8575Minimal with interrupt support.

    Adds configure_interrupt() to attach a callback to the chip's active-low
    INT output, and clear_interrupt() to read the current pin states and
    return the 16-bit bitmask of pins that changed since the last read.

    On MicroPython, pass a hardware machine.Pin (INT line) to configure_interrupt.
    On Linux, pass int_pin=None to use a 5 ms polling thread instead.

    Args:
        transport: Configured I²C transport pointing at the device.
        addr: 7-bit I²C device address (default 0x20).
    """

    IRQ_RISING  = 0x01
    IRQ_FALLING = 0x02

    def __init__(self, transport, addr=0x20):
        super().__init__(transport, addr)
        self._prev = [0xFF, 0xFF]
        self._callback = None
        self._int_pin = None
        self._poll_thread = None
        self._poll_stop = False
        raw = self._transport.read(2)
        self._prev = list(raw)

    def configure_interrupt(self, int_pin, callback):
        """Attach a callback to the chip's INT output.

        The callback receives one argument: a 16-bit bitmask of pins that
        changed since the previous read (1 = changed, 0 = stable). Bits 0–7
        are Port 0 (P00–P07); bits 8–15 are Port 1 (P10–P17).

        On MicroPython/CircuitPython, int_pin must be a hardware
        machine.Pin or countio.Counter-compatible object pre-configured
        as an input; the driver calls irq() on it.

        On Linux, pass int_pin=None to deliver interrupts via a 5 ms
        polling thread.

        Args:
            int_pin: Hardware interrupt pin, or None for polling (Linux only).
            callback: Callable(changed_mask: int) invoked on any input change.
        """
        self._callback = callback
        self._int_pin = int_pin
        if int_pin is None and _LINUX:
            self._poll_stop = False
            self._poll_thread = _threading.Thread(target=self._poll_loop, daemon=True)
            self._poll_thread.start()
        elif int_pin is not None:
            try:
                import machine
                int_pin.irq(trigger=machine.Pin.IRQ_FALLING, handler=self._irq_handler)
            except ImportError:
                pass

    def _irq_handler(self, pin):
        changed = self.clear_interrupt()
        if changed and self._callback:
            self._callback(changed)

    def _poll_loop(self):
        import time
        while not self._poll_stop:
            current = self._transport.read(2)
            changed0 = current[0] ^ self._prev[0]
            changed1 = current[1] ^ self._prev[1]
            changed = changed0 | (changed1 << 8)
            if changed and self._callback:
                self._prev = list(current)
                self._callback(changed)
            time.sleep(0.005)

    def clear_interrupt(self):
        """Read current pin states and return the 16-bit changed-pin bitmask.

        Performs a 2-byte I²C read, compares to the previous read, updates the
        stored previous values, and returns the XOR (bits 0–7 = Port 0 changed,
        bits 8–15 = Port 1 changed). Reading also clears the chip's INT output.

        Returns:
            int: 16-bit bitmask; bit n = 1 if pin n changed since last read.
        """
        current = self._transport.read(2)
        changed0 = current[0] ^ self._prev[0]
        changed1 = current[1] ^ self._prev[1]
        self._prev = list(current)
        return changed0 | (changed1 << 8)

    # ------------------------------------------------------------------
    # MicroPython / Linux Pin proxy — Full (adds irq)
    # ------------------------------------------------------------------

    class _Pin(Pcf8575Minimal._Pin):
        """Full GPIO proxy — adds irq() for interrupt-driven input.

        Args:
            chip: Parent Pcf8575Full instance.
            n: Pin index (0–15).
        """

        def irq(self, handler, trigger):
            """Register an interrupt handler for this pin.

            The handler is called with this pin as the sole argument when
            the pin's state matches the trigger. Internally uses the chip's
            configure_interrupt mechanism; the chip's INT line must be
            connected to a hardware GPIO and passed to Pcf8575Full at
            configure_interrupt() call time.

            Args:
                handler: Callable(pin) to invoke on trigger.
                trigger: Pcf8575Full.IRQ_RISING or Pcf8575Full.IRQ_FALLING.
            """
            n = self._n
            chip = self._chip
            trigger_val = 1 if trigger == Pcf8575Full.IRQ_RISING else 0

            def _wrap(changed_mask):
                if (changed_mask >> n) & 1:
                    port = n // 8
                    current = (chip._read_port_raw(port) >> (n % 8)) & 1
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

    # ------------------------------------------------------------------
    # CircuitPython Pin proxy — Full (pull/drive_mode raise informative errors)
    # ------------------------------------------------------------------

    class _CPPin(Pcf8575Minimal._CPPin):
        """Full GPIO proxy for CircuitPython — raises AttributeError for unsupported features.

        Args:
            chip: Parent Pcf8575Full instance.
            n: Pin index (0–15).
        """

        @property
        def pull(self):
            """Not supported: the PCF8575 has a fixed internal pull-up."""
            raise AttributeError("PCF8575 has a fixed internal pull-up; pull cannot be configured")

        @pull.setter
        def pull(self, v):
            raise AttributeError("PCF8575 has a fixed internal pull-up; pull cannot be configured")

        @property
        def drive_mode(self):
            """Not supported: the PCF8575 output is always open-drain."""
            raise AttributeError("PCF8575 output is always open-drain; drive_mode cannot be configured")

        @drive_mode.setter
        def drive_mode(self, v):
            raise AttributeError("PCF8575 output is always open-drain; drive_mode cannot be configured")