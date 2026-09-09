class I2CConnectionMock:
    """In-memory fake I2C connection for unit tests — no hardware, no bus.

    Supports the two access patterns chip drivers in this repo use:

    - Register-addressed reads (``write_read(bytes([reg]), n)``): backed by a
      byte-addressable ``registers`` dict. Preload it via the constructor or
      ``set_register`` before constructing the chip.
    - Plain streamed reads (``read(n)``, no register address): backed by a
      FIFO queue. Preload responses with ``queue_read``; each ``read(n)`` call
      pops the next one. Falls back to ``n`` zero bytes if the queue is empty.

    Every ``write()`` call (register writes and plain command writes alike)
    is appended to ``writes`` for assertions, and register writes are also
    applied to ``registers`` so a later ``write_read`` sees them.
    """

    def __init__(self, registers=None):
        self.registers = dict(registers or {})
        self.writes = []
        self._read_queue = []

    def set_register(self, reg, *values):
        """Preload consecutive register bytes starting at ``reg``."""
        for i, value in enumerate(values):
            self.registers[reg + i] = value

    def queue_read(self, data):
        """Queue bytes to be returned by the next plain ``read(n)`` call."""
        self._read_queue.append(bytes(data))

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        if len(data) >= 2:
            reg = data[0]
            for i, value in enumerate(data[1:]):
                self.registers[reg + i] = value

    def read(self, n):
        if self._read_queue:
            return self._read_queue.pop(0)
        return bytes(n)

    def write_read(self, data, n):
        self.writes.append(bytes(data))
        reg = data[0]
        return bytes(self.registers.get(reg + i, 0) for i in range(n))

    def close(self):
        pass
