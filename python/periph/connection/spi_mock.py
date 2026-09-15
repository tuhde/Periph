class SPIConnectionMock:
    """In-memory fake SPI connection for unit tests — no hardware, no bus.

    Models full-duplex SPI: every transaction sends and receives
    simultaneously. Two preloading patterns are supported:

    - **Plain reads via `queue_read(data)`** — each `read(n)` call pops
      the next queued response. Plain `read` calls (no command phase)
      are used by chip drivers like RFM9x's burst-read path.
    - **Command-then-read via `set_register(reg, values)`** — when the
      chip calls `write_read([cmd_byte], n)`, the mock treats `cmd_byte`
      as a register address and returns `n` consecutive register bytes
      starting at that address. This mirrors how RFM9x addresses SPI
      registers by a (cmd & 0x7F) byte.

    Write phases are recorded on the `writes` log for assertions. Any
    `write(data)` of 2+ bytes also updates the register map at the
    command-byte address (same convention as I2CConnectionMock), so a
    write followed by a read sees the write's effect.

    Args:
        mode: SPI mode 0–3 (default 0). Recorded for completeness;
            the mock does not model clock polarity/phase.
        max_speed_hz: Maximum clock frequency in Hz. Recorded only.
    """

    def __init__(self, mode=0, max_speed_hz=1_000_000):
        self.mode = mode
        self.max_speed_hz = max_speed_hz
        self._registers = {}
        self._writes = []
        self._read_queue = []

    def set_register(self, reg, values):
        """Preload consecutive register bytes starting at ``reg``.

        Mirrors ``I2CConnectionMock.set_register``.
        """
        if isinstance(values, int):
            values = (values,)
        for i, value in enumerate(values):
            self._registers[reg + i] = value & 0xFF

    def queue_read(self, data):
        """Queue bytes returned by the next plain ``read(n)`` call."""
        if isinstance(data, int):
            data = (data,)
        self._read_queue.append(bytes(data))

    @property
    def writes(self):
        """Log of every write/write_read command phase."""
        return self._writes

    @property
    def registers(self):
        """Direct access to the register map for assertions."""
        return self._registers

    def write(self, data):
        data = bytes(data)
        self._writes.append(data)
        if len(data) >= 2:
            reg = data[0]
            for i, value in enumerate(data[1:]):
                self._registers[reg + i] = value

    def read(self, n):
        if self._read_queue:
            front = self._read_queue.pop(0)
            out = bytearray(n)
            for i in range(min(n, len(front))):
                out[i] = front[i]
            return bytes(out)
        return bytes(n)

    def write_read(self, data, n):
        """Full-duplex write-then-read.

        The command phase (`len(data)` bytes) is recorded; the read
        phase (`n` bytes) is filled from the register map keyed by the
        first command byte. This mirrors how SPI chip drivers in this
        repo use `write_read([cmd], n)` to read back a register or
        status byte addressed by the SPI instruction byte.
        """
        data = bytes(data)
        self._writes.append(data)
        out = bytearray(n)
        if data:
            reg = data[0]
            for i in range(n):
                out[i] = self._registers.get(reg + i, 0)
        return bytes(out)

    def close(self):
        pass
