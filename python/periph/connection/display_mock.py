from .display_base import WireMode


class DisplayConnectionMock:
    """In-memory fake display connection for unit tests — no hardware, no bus.

    Has the public interface of the real connections (send_command, send_data,
    read_data, reset, wait_busy, busy_level, enable/disable, close) and records
    every call in order in `events`, so a chip-driver test can assert the exact
    command stream and the reset/BUSY handling.

    `events` holds tuples: ('reset',), ('command', cmd, bytes), ('data', bytes),
    ('read', length), ('wait_busy', busy_active_high, timeout_ms), ('delay', ms).

    Args:
        wire_mode: A WireMode value (default SIMPLEX). read_data() raises in SIMPLEX,
            as the real connection does.
    """

    def __init__(self, wire_mode=WireMode.SIMPLEX):
        self.wire_mode = wire_mode
        self.events = []
        self.ready = True          # wait_busy() result; set False to simulate a timeout
        self.busy_high = False     # raw level returned by busy_level()
        self._read_queue = bytearray()
        self._enabled = True

    def queue_read(self, data):
        """Queue bytes that later read_data() calls return, in order.

        Args:
            data: Bytes the simulated controller answers with.
        """
        self._read_queue += bytes(data)

    @property
    def commands(self):
        """list: (cmd, parameter bytes) for every send_command() so far."""
        return [(e[1], e[2]) for e in self.events if e[0] == 'command']

    @property
    def reset_count(self):
        """int: Number of reset() calls so far."""
        return sum(1 for e in self.events if e[0] == 'reset')

    def enable(self):
        """Resume operation."""
        self._enabled = True

    def disable(self):
        """Gate bus operations -- they become no-ops until enable()."""
        self._enabled = False

    def is_enabled(self):
        """Return the current software-gate state."""
        return self._enabled

    def reset(self):
        """Record a hardware reset."""
        if self._enabled:
            self.events.append(('reset',))

    def delay_ms(self, ms):
        """Record a delay (no real wait)."""
        self.events.append(('delay', ms))

    def busy_level(self):
        """Return the configured raw BUSY level."""
        return self.busy_high

    def wait_busy(self, busy_active_high, timeout_ms=None):
        """Record the wait and return `ready`."""
        self.events.append(('wait_busy', busy_active_high, timeout_ms))
        return self.ready

    def send_command(self, cmd, data=b''):
        """Record a command with its parameter bytes."""
        if self._enabled:
            self.events.append(('command', cmd, bytes(data)))

    def send_data(self, data):
        """Record a data write."""
        if self._enabled:
            self.events.append(('data', bytes(data)))

    def read_data(self, length):
        """Return `length` queued bytes (zero-padded when the queue runs dry).

        Raises:
            RuntimeError: In SIMPLEX wire mode.
        """
        if self.wire_mode == WireMode.SIMPLEX:
            raise RuntimeError('read_data() is not available in SIMPLEX wire mode')
        if not self._enabled:
            return bytes(length)
        self.events.append(('read', length))
        out = bytes(self._read_queue[:length])
        del self._read_queue[:length]
        return out + bytes(length - len(out))

    def close(self):
        pass
