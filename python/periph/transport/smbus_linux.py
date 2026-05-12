from smbus2 import SMBus, i2c_msg

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
    """SMBus transport for Linux (wraps smbus2 with address validation and PEC).

    Accepts either a bus number (opens /dev/i2c-N itself) or an already-opened
    SMBus instance. Enforces the valid 7-bit SMBus address range (0x08–0x77)
    and, when pec=True, appends a CRC-8 byte to writes and verifies it on reads.
    PEC is computed in software using raw i2c_rdwr transfers.
    Call close() to release the bus when constructed with a bus number.

    Args:
        bus: Bus number (int, opens /dev/i2c-N) or an open smbus2.SMBus instance.
        addr: 7-bit device address (0x08–0x77).
        pec: Enable Packet Error Code (CRC-8) checking (default False).

    Raises:
        ValueError: If addr is outside the valid SMBus range.
    """

    def __init__(self, bus, addr, pec=False):
        if not (0x08 <= addr <= 0x77):
            raise ValueError("SMBus address must be in range 0x08-0x77")
        if isinstance(bus, int):
            self._bus = SMBus(bus)
            self._owns_bus = True
        else:
            self._bus = bus
            self._owns_bus = False
        self._addr = addr
        self._pec = pec

    def write(self, data):
        """Send bytes to the device via i2c_rdwr, appending a PEC byte if enabled.

        Args:
            data: Bytes to write.

        Raises:
            OSError: On no ACK or bus error.
        """
        if self._pec:
            data = bytes(data) + bytes([_crc8(bytes([self._addr << 1]) + bytes(data))])
        self._bus.i2c_rdwr(i2c_msg.write(self._addr, list(data)))

    def read(self, n):
        """Read bytes from the device via i2c_rdwr, verifying the PEC byte if enabled.

        Reads n+1 bytes when PEC is enabled and strips the trailing CRC byte.

        Args:
            n: Number of data bytes to read.

        Returns:
            bytes: The n data bytes (PEC byte stripped if enabled).

        Raises:
            OSError: On PEC mismatch or bus error.
        """
        msg = i2c_msg.read(self._addr, n + 1 if self._pec else n)
        self._bus.i2c_rdwr(msg)
        raw = bytes(msg)
        if self._pec:
            if _crc8(bytes([(self._addr << 1) | 1]) + raw[:-1]) != raw[-1]:
                raise OSError("SMBus PEC error")
            return raw[:-1]
        return raw

    def write_read(self, data, n):
        """Write then read in a single i2c_rdwr call, with optional PEC verification.

        Both messages are submitted together so the kernel issues a repeated
        START between them without releasing the bus. PEC covers the full
        transaction: write address + write data + read address + read data.

        Args:
            data: Bytes to write (typically a register address).
            n: Number of data bytes to read back.

        Returns:
            bytes: The n data bytes (PEC byte stripped if enabled).

        Raises:
            OSError: On PEC mismatch or bus error.
        """
        write_msg = i2c_msg.write(self._addr, list(data))
        read_msg = i2c_msg.read(self._addr, n + 1 if self._pec else n)
        self._bus.i2c_rdwr(write_msg, read_msg)
        raw = bytes(read_msg)
        if self._pec:
            expected = _crc8(
                bytes([self._addr << 1]) + bytes(data) +
                bytes([(self._addr << 1) | 1]) + raw[:-1]
            )
            if expected != raw[-1]:
                raise OSError("SMBus PEC error")
            return raw[:-1]
        return raw

    def close(self):
        """Release the bus. Only closes the underlying SMBus if this instance opened it."""
        if self._owns_bus:
            self._bus.close()
