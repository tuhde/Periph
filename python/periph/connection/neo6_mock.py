class NEO6ConnectionMock:
    """In-memory fake connection for NEO-6 unit tests — no hardware, no bus.

    NEO-6's driver treats UART, I2C (DDC), and SPI as the same underlying
    NMEA/UBX byte stream (see specs/gnss/neo-6.md's "Connection
    abstraction" note): ``read(1)`` for UART, ``write_read(b'\\xff', 1)``
    for I2C, ``write_read(b'', 1)`` for SPI. This mock is transport-shape
    agnostic: preload the stream with ``queue_bytes(data)``; ``read()`` and
    ``write_read()`` both just pop the next byte(s) off the front of one
    shared FIFO, regardless of the prefix passed to ``write_read`` —
    matching the real module, where all three transports deliver the same
    bytes and only the framing differs. ``write()`` calls (e.g. send_ubx)
    are logged to ``writes`` for assertions.
    """

    def __init__(self):
        self._stream = bytearray()
        self.writes = []

    def queue_bytes(self, data):
        """Append bytes to the end of the shared read stream."""
        self._stream.extend(data)

    def read(self, n):
        out = bytes(self._stream[:n])
        del self._stream[:len(out)]
        return out

    def write_read(self, data, n):
        return self.read(n)

    def write(self, data):
        self.writes.append(bytes(data))

    def close(self):
        pass
