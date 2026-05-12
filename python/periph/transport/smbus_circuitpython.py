from .base import Transport


def _crc8(data):
    crc = 0
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc << 1) ^ 0x07 if crc & 0x80 else crc << 1
        crc &= 0xFF
    return crc


class SMBusTransport(Transport):
    """SMBus transport for CircuitPython (wraps busio.I2C with address validation and PEC).

    Acquires and releases the bus lock around every operation. Enforces the
    valid 7-bit SMBus address range (0x08–0x77) and, when pec=True, appends a
    CRC-8 byte to writes and verifies it on reads.

    Args:
        bus: Configured busio.I2C instance.
        addr: 7-bit device address (0x08–0x77).
        pec: Enable Packet Error Code (CRC-8) checking (default False).

    Raises:
        ValueError: If addr is outside the valid SMBus range.
    """

    def __init__(self, bus, addr, pec=False):
        if not (0x08 <= addr <= 0x77):
            raise ValueError("SMBus address must be in range 0x08-0x77")
        self._bus = bus
        self._addr = addr
        self._pec = pec

    def write(self, data):
        """Send bytes to the device, appending a PEC byte if enabled.

        Acquires the bus lock for the duration of the write.

        Args:
            data: Bytes to write.

        Raises:
            OSError: On no ACK or bus timeout.
        """
        if self._pec:
            data = bytes(data) + bytes([_crc8(bytes([self._addr << 1]) + bytes(data))])
        while not self._bus.try_lock():
            pass
        try:
            self._bus.writeto(self._addr, bytes(data))
        finally:
            self._bus.unlock()

    def read(self, n):
        """Read bytes from the device, verifying the PEC byte if enabled.

        Reads n+1 bytes when PEC is enabled and strips the trailing CRC byte.

        Args:
            n: Number of data bytes to read.

        Returns:
            bytes: The n data bytes (PEC byte stripped if enabled).

        Raises:
            OSError: On PEC mismatch or bus timeout.
        """
        buf = bytearray(n + 1 if self._pec else n)
        while not self._bus.try_lock():
            pass
        try:
            self._bus.readfrom_into(self._addr, buf)
        finally:
            self._bus.unlock()
        if self._pec:
            if _crc8(bytes([(self._addr << 1) | 1]) + bytes(buf[:-1])) != buf[-1]:
                raise OSError("SMBus PEC error")
            return bytes(buf[:-1])
        return bytes(buf)

    def write_read(self, data, n):
        """Write then read with optional PEC verification.

        PEC covers the full transaction: write address + write data +
        read address + read data.

        Args:
            data: Bytes to write (typically a register address).
            n: Number of data bytes to read back.

        Returns:
            bytes: The n data bytes (PEC byte stripped if enabled).

        Raises:
            OSError: On PEC mismatch or bus timeout.
        """
        buf = bytearray(n + 1 if self._pec else n)
        while not self._bus.try_lock():
            pass
        try:
            self._bus.writeto_then_readfrom(self._addr, bytes(data), buf)
        finally:
            self._bus.unlock()
        if self._pec:
            expected = _crc8(
                bytes([self._addr << 1]) + bytes(data) +
                bytes([(self._addr << 1) | 1]) + bytes(buf[:-1])
            )
            if expected != buf[-1]:
                raise OSError("SMBus PEC error")
            return bytes(buf[:-1])
        return bytes(buf)
