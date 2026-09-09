class DHTxxConnectionMock:
    """In-memory fake DHTxx connection for unit tests — no hardware, no GPIO.

    DHTxx has no register map and no framing to fake at this level: the
    chip driver only ever calls ``read()`` and gets back a raw 5-byte
    frame (or an exception propagating straight through, e.g. a real
    connection's timeout/framing ``DHTxxError``). Preload frames with
    ``queue_read``; preload an exception to raise instead with
    ``queue_exception``. Each ``read()`` call pops the next queued item
    (falling back to 5 zero bytes if the queue is empty, matching the real
    connections' disabled-state return value).
    """

    def __init__(self):
        self._queue = []

    def queue_read(self, frame):
        """Queue a raw 5-byte frame to be returned by the next read()."""
        self._queue.append(bytes(frame))

    def queue_exception(self, exc):
        """Queue an exception instance to be raised by the next read()."""
        self._queue.append(exc)

    def read(self):
        if not self._queue:
            return bytes(5)
        item = self._queue.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item

    def close(self):
        pass
