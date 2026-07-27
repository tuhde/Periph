from abc import ABC, abstractmethod


class InputPin(ABC):
    """Delivers edge notifications from a chip's INT line.

    Intentionally minimal: it only signals that *an* edge occurred. The chip
    driver always calls poll_interrupt() to determine the cause. Multiple
    handlers may be registered on the same InputPin instance — the common
    pattern for chips with open-drain INT outputs wired to a single GPIO.
    """

    RISING = 1
    FALLING = 2
    CHANGE = 3

    @abstractmethod
    def on_edge(self, handler, trigger=FALLING):
        """Append handler() to the edge-notification list for the given trigger.

        Args:
            handler: Zero-argument callable invoked on each matching edge.
            trigger: One of RISING, FALLING, CHANGE (default FALLING).
        """
        raise NotImplementedError

    @abstractmethod
    def off_edge(self, handler):
        """Remove a specific handler from the list. No-op if not registered.

        Args:
            handler: The exact callable previously passed to on_edge().
        """
        raise NotImplementedError


class MicroPythonPin(InputPin):
    """InputPin for MicroPython, backed by machine.Pin.irq().

    machine.Pin only supports one native IRQ callback; this class fans a
    single hardware IRQ out to a list of registered handlers so multiple
    chips can share one physical INT line.

    Args:
        pin: Configured machine.Pin instance (input, with pull-up if needed).
    """

    _TRIGGER_MAP = None  # populated lazily; machine.Pin constants aren't importable off-target

    def __init__(self, pin):
        self._pin = pin
        self._handlers = []
        self._trigger = InputPin.FALLING
        self._armed = False

    def on_edge(self, handler, trigger=InputPin.FALLING):
        import machine

        if MicroPythonPin._TRIGGER_MAP is None:
            MicroPythonPin._TRIGGER_MAP = {
                InputPin.RISING: machine.Pin.IRQ_RISING,
                InputPin.FALLING: machine.Pin.IRQ_FALLING,
                InputPin.CHANGE: machine.Pin.IRQ_RISING | machine.Pin.IRQ_FALLING,
            }
        self._handlers.append(handler)
        self._trigger = trigger
        if not self._armed:
            self._pin.irq(trigger=MicroPythonPin._TRIGGER_MAP[trigger], handler=self._dispatch)
            self._armed = True

    def off_edge(self, handler):
        if handler in self._handlers:
            self._handlers.remove(handler)
        if not self._handlers and self._armed:
            self._pin.irq(handler=None)
            self._armed = False

    def _dispatch(self, pin):
        for handler in self._handlers:
            handler()


class CircuitPythonPin(InputPin):
    """InputPin for CircuitPython.

    CircuitPython has no universal hardware-callback API across boards, so
    this delivers edges via a background thread (where _thread is available)
    that polls the pin's digital value and compares it against the previous
    sample — the same mechanism LinuxPollingPin uses on the host side.

    Args:
        pin: Configured digitalio.DigitalInOut instance (input, with pull as needed).
        poll_interval_ms: Polling period in milliseconds (default 5).
    """

    def __init__(self, pin, poll_interval_ms=5):
        self._pin = pin
        self._poll_interval_ms = poll_interval_ms
        self._handlers = []
        self._trigger = InputPin.FALLING
        self._last = pin.value
        self._running = False

    def on_edge(self, handler, trigger=InputPin.FALLING):
        self._handlers.append(handler)
        self._trigger = trigger
        if not self._running:
            self._start()

    def off_edge(self, handler):
        if handler in self._handlers:
            self._handlers.remove(handler)
        if not self._handlers:
            self._running = False

    def _start(self):
        try:
            import _thread
        except ImportError:
            raise RuntimeError(
                "CircuitPythonPin requires _thread support on this board; "
                "poll the pin manually instead")
        self._running = True
        _thread.start_new_thread(self._loop, ())

    def _loop(self):
        import time

        while self._running:
            value = self._pin.value
            if value != self._last:
                rising = value and not self._last
                falling = self._last and not value
                if (self._trigger == InputPin.CHANGE
                        or (self._trigger == InputPin.RISING and rising)
                        or (self._trigger == InputPin.FALLING and falling)):
                    for handler in list(self._handlers):
                        handler()
                self._last = value
            time.sleep(self._poll_interval_ms / 1000.0)


class LinuxPollingPin(InputPin):
    """InputPin for Linux hosts without direct GPIO access: a polling thread.

    Args:
        read_value: Zero-argument callable returning the current pin state (bool).
        poll_interval_ms: Polling period in milliseconds (default 5).
    """

    def __init__(self, read_value, poll_interval_ms=5):
        self._read_value = read_value
        self._poll_interval_ms = poll_interval_ms
        self._handlers = []
        self._trigger = InputPin.FALLING
        self._last = read_value()
        self._thread = None
        self._running = False

    def on_edge(self, handler, trigger=InputPin.FALLING):
        import threading

        self._handlers.append(handler)
        self._trigger = trigger
        if self._thread is None:
            self._running = True
            self._thread = threading.Thread(target=self._loop, daemon=True)
            self._thread.start()

    def off_edge(self, handler):
        if handler in self._handlers:
            self._handlers.remove(handler)
        if not self._handlers:
            self._running = False

    def _loop(self):
        import time

        while self._running:
            value = self._read_value()
            if value != self._last:
                rising = value and not self._last
                falling = self._last and not value
                if (self._trigger == InputPin.CHANGE
                        or (self._trigger == InputPin.RISING and rising)
                        or (self._trigger == InputPin.FALLING and falling)):
                    for handler in list(self._handlers):
                        handler()
                self._last = value
            time.sleep(self._poll_interval_ms / 1000.0)


class LinuxSysfsPin(InputPin):
    """InputPin for Linux, using select() on a gpiod v2 edge-event line request.

    Lower latency than LinuxPollingPin — blocks in a background thread on the
    kernel's edge-event file descriptor instead of sampling on an interval.

    Args:
        chip_path: GPIO chip device path (e.g. '/dev/gpiochip0').
        line_offset: GPIO line offset to watch for edges.
    """

    def __init__(self, chip_path, line_offset):
        self._chip_path = chip_path
        self._line_offset = line_offset
        self._handlers = []
        self._trigger = InputPin.FALLING
        self._request = None
        self._thread = None
        self._running = False

    def on_edge(self, handler, trigger=InputPin.FALLING):
        import threading

        self._handlers.append(handler)
        self._trigger = trigger
        if self._thread is None:
            self._open_request()
            self._running = True
            self._thread = threading.Thread(target=self._loop, daemon=True)
            self._thread.start()

    def off_edge(self, handler):
        if handler in self._handlers:
            self._handlers.remove(handler)
        if not self._handlers:
            self._running = False
            if self._request is not None:
                self._request.release()
                self._request = None

    def _open_request(self):
        import gpiod
        from gpiod.line import Direction, Edge

        edge_map = {
            InputPin.RISING: Edge.RISING,
            InputPin.FALLING: Edge.FALLING,
            InputPin.CHANGE: Edge.BOTH,
        }
        settings = gpiod.LineSettings(
            direction=Direction.INPUT, edge_detection=edge_map[self._trigger])
        chip = gpiod.Chip(self._chip_path)
        self._request = chip.request_lines(
            consumer="periph-int", line_config={self._line_offset: settings})

    def _loop(self):
        import select

        fd = self._request.fd
        while self._running:
            ready, _, _ = select.select([fd], [], [], 0.1)
            if not self._running:
                break
            if ready:
                for _event in self._request.read_edge_events():
                    for handler in list(self._handlers):
                        handler()
