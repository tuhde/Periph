from .base import Transport


class I2CTransport(Transport):
    """I²C transport for CircuitPython (wraps busio.I2C).

    Acquires and releases the bus lock around every operation.

    Args:
        bus: Configured busio.I2C instance.
        addr: 7-bit device address.
    """

    def __init__(self, bus, addr):
        self._bus = bus
        self._addr = addr

    def write(self, data):
        """Send bytes to the device.

        Acquires the bus lock, writes, then releases the lock.

        Args:
            data: Bytes to write.

        Raises:
            OSError: On no ACK or bus timeout.
        """
        while not self._bus.try_lock():
            pass
        try:
            self._bus.writeto(self._addr, bytes(data))
        finally:
            self._bus.unlock()

    def read(self, n):
        """Read bytes from the device.

        Acquires the bus lock, reads into a buffer, then releases the lock.

        Args:
            n: Number of bytes to read.

        Returns:
            bytes: Data received from the device.

        Raises:
            OSError: On no ACK or bus timeout.
        """
        buf = bytearray(n)
        while not self._bus.try_lock():
            pass
        try:
            self._bus.readfrom_into(self._addr, buf)
        finally:
            self._bus.unlock()
        return bytes(buf)

    def write_read(self, data, n):
        """Write then read using writeto_then_readfrom (repeated start).

        Acquires the bus lock for the combined transaction.

        Args:
            data: Bytes to write (typically a register address).
            n: Number of bytes to read back.

        Returns:
            bytes: Data received from the device.

        Raises:
            OSError: On no ACK or bus timeout.
        """
        buf = bytearray(n)
        while not self._bus.try_lock():
            pass
        try:
            self._bus.writeto_then_readfrom(self._addr, bytes(data), buf)
        finally:
            self._bus.unlock()
        return bytes(buf)
