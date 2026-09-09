class HX711ConnectionMock:
    """In-memory fake HX711 connection for unit tests — no hardware, no GPIO.

    HX711 has no register map; each conversion is instead selected by the
    pulse count (25/26/27) sent to ``read_raw``. Preload signed 24-bit
    conversion results with ``queue_read``; each ``read_raw`` call pops the
    next one (0 if the queue is empty) and, matching every real connection's
    contract, validates ``num_pulses`` is one of {25, 26, 27}, raising
    ``ValueError`` otherwise. Every call's pulse count is appended to
    ``reads`` so tests can assert which channel/gain a driver call actually
    requested (e.g. that ``set_gain`` drives the right pulse count).
    ``power_down``/``power_up`` calls are logged to ``power_calls``.
    """

    def __init__(self):
        self._queue = []
        self.reads = []
        self.power_calls = []
        self.ready = True

    def queue_read(self, value):
        """Queue a signed 24-bit value to be returned by the next read_raw()."""
        self._queue.append(value)

    def is_ready(self):
        return self.ready

    def read_raw(self, num_pulses):
        if num_pulses not in (25, 26, 27):
            raise ValueError("num_pulses must be 25, 26, or 27")
        self.reads.append(num_pulses)
        if self._queue:
            return self._queue.pop(0)
        return 0

    def power_down(self):
        self.power_calls.append('down')

    def power_up(self):
        self.power_calls.append('up')

    def close(self):
        pass
