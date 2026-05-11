from smbus2 import SMBus, i2c_msg

from .base import Transport


class I2CTransport(Transport):
    """I²C transport for Linux (wraps smbus2, uses /dev/i2c-N).

    Accepts either a bus number (opens the device file itself) or an
    already-opened SMBus instance. Call close() to release the bus when
    constructed with a bus number.

    Args:
        bus: Bus number (int, opens /dev/i2c-N) or an open smbus2.SMBus instance.
        addr: 7-bit device address.
    """

    def __init__(self, bus, addr):
        if isinstance(bus, int):
            self._bus = SMBus(bus)
            self._owns_bus = True
        else:
            self._bus = bus
            self._owns_bus = False
        self._addr = addr

    def write(self, data):
        """Send bytes to the device via i2c_rdwr.

        Args:
            data: Bytes to write.

        Raises:
            OSError: On no ACK or bus error.
        """
        self._bus.i2c_rdwr(i2c_msg.write(self._addr, list(data)))

    def read(self, n):
        """Read bytes from the device via i2c_rdwr.

        Args:
            n: Number of bytes to read.

        Returns:
            bytes: Data received from the device.

        Raises:
            OSError: On no ACK or bus error.
        """
        msg = i2c_msg.read(self._addr, n)
        self._bus.i2c_rdwr(msg)
        return bytes(msg)

    def write_read(self, data, n):
        """Write then read in a single i2c_rdwr call (repeated start).

        Both messages are submitted together so the kernel issues a repeated
        START between them without releasing the bus.

        Args:
            data: Bytes to write (typically a register address).
            n: Number of bytes to read back.

        Returns:
            bytes: Data received from the device.

        Raises:
            OSError: On no ACK or bus error.
        """
        write_msg = i2c_msg.write(self._addr, list(data))
        read_msg = i2c_msg.read(self._addr, n)
        self._bus.i2c_rdwr(write_msg, read_msg)
        return bytes(read_msg)

    def close(self):
        """Release the bus. Only closes the underlying SMBus if this instance opened it."""
        if self._owns_bus:
            self._bus.close()
