class SiPoConnectionMock:
    """In-memory fake SiPo connection for unit tests — no hardware, no bus.

    Models the write-only shift-register protocol chip drivers in this repo
    use (TPIC6B595, SN74HC595, ...): `write(data)` shifts + latches, `clear()`
    pulses SRCLR, `set_output_enable(enabled)` drives G. SRCLR/G availability
    is configurable at construction, matching the real connection's
    `RuntimeError` when the corresponding GPIO line wasn't wired.

    Args:
        has_srclr: True if SRCLR is "wired" — clear() succeeds and is logged.
        has_g: True if G is "wired" — set_output_enable() succeeds and is logged.
    """

    def __init__(self, has_srclr=True, has_g=True):
        self.writes = []
        self.clear_count = 0
        self.output_enable_calls = []
        self._has_srclr = has_srclr
        self._has_g = has_g
        self._enabled = True

    def enable(self):
        """Resume writes."""
        self._enabled = True

    def disable(self):
        """Gate write() -- becomes a no-op until enable()."""
        self._enabled = False

    def is_enabled(self):
        """Return the current software-gate state."""
        return self._enabled

    def write(self, data):
        """Record a shift+latch write. No-op if disabled."""
        if not self._enabled:
            return
        self.writes.append(bytes(data))

    def clear(self):
        """Record an SRCLR pulse.

        Raises:
            RuntimeError: If constructed with has_srclr=False.
        """
        if not self._has_srclr:
            raise RuntimeError('SRCLR not configured')
        self.clear_count += 1

    def set_output_enable(self, enabled):
        """Record a G drive.

        Raises:
            RuntimeError: If constructed with has_g=False.
        """
        if not self._has_g:
            raise RuntimeError('G not configured')
        self.output_enable_calls.append(enabled)

    def close(self):
        pass
