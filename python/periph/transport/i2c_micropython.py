from .base import Transport


class I2CTransport(Transport):
    """I²C transport for MicroPython (wraps machine.I2C / machine.SoftI2C).

    One instance represents one device on the bus; addr is fixed at construction.

    Args:
        bus: Configured machine.I2C or machine.SoftI2C instance.
        addr: 7-bit device address.
    """

    def __init__(self, bus, addr):
        self._bus = bus
        self._addr = addr

    def write(self, data):
        """Send bytes to the device.

        Args:
            data: Bytes to write.

        Raises:
            OSError: On no ACK or bus timeout.
        """
        self._bus.writeto(self._addr, data)

    def read(self, n):
        """Read bytes from the device.

        Args:
            n: Number of bytes to read.

        Returns:
            bytes: Data received from the device.

        Raises:
            OSError: On no ACK or bus timeout.
        """
        return self._bus.readfrom(self._addr, n)

    def write_read(self, data, n):
        """Write then read using a repeated start (no STOP between phases).

        Suppresses the STOP after the write so the following read issues a
        repeated START instead of beginning a new transaction.

        Args:
            data: Bytes to write (typically a register address).
            n: Number of bytes to read back.

        Returns:
            bytes: Data received from the device.

        Raises:
            OSError: On no ACK or bus timeout.
        """
        buf = bytearray(n)
        self._bus.writeto(self._addr, data, False)  # False = no STOP → repeated start
        self._bus.readfrom_into(self._addr, buf)
        return bytes(buf)
