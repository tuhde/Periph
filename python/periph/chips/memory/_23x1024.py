class SRAM23X1024Minimal:
    """23LC1024 / 23A1024 1-Mbit SPI serial SRAM — minimal interface.

    Byte read/write access to the 131,072-byte array over plain SPI (Mode 0).
    The chip is volatile but has unlimited cycles and zero write time: a write
    is complete when CS is released, with no polling.

    Default configuration:
        - Sequential mode (MODE register = 0x40), written and read back at
          construction; a mismatch (floating/missing MISO, wrong SPI mode,
          chip stuck in SDI/SQI mode) raises OSError.
        - Full 128 KiB addressable; SDI/SQI bus modes are never used.

    Every transaction is one instruction byte, a 24-bit big-endian address and
    data, with CS held low for the whole frame, so the driver takes the bare
    Connection and uses write()/write_read() directly (the instruction is not
    a register address, so RegisterConnection's read-bit logic does not apply).

    Args:
        connection: SPI Connection (Mode 0, MSB first, CS managed by the connection).
    """

    SIZE = 131072

    _CMD_READ = 0x03
    _CMD_WRITE = 0x02
    _CMD_RDMR = 0x05
    _CMD_WRMR = 0x01
    _CMD_RSTIO = 0xFF
    _MODE_MASK = 0xC0

    def __init__(self, connection):
        self._connection = connection
        self._write_mode(0x40)  # Sequential (power-on default, written for idempotency)
        if self._read_mode() != 0x40:
            raise OSError("23X1024 MODE register mismatch: check wiring and SPI Mode 0")

    def _read_mode(self):
        return self._connection.write_read(bytes([self._CMD_RDMR]), 1)[0]

    def _write_mode(self, mode):
        self._connection.write(bytes([self._CMD_WRMR, mode]))

    @staticmethod
    def _header(command, address):
        return bytes([command, (address >> 16) & 0xFF, (address >> 8) & 0xFF, address & 0xFF])

    def _read_raw(self, address, length):
        return bytes(self._connection.write_read(self._header(self._CMD_READ, address), length))

    def _write_raw(self, address, data):
        self._connection.write(self._header(self._CMD_WRITE, address) + bytes(data))

    def read_byte(self, address):
        """Read one byte (READ, 0x03).

        Args:
            address: 0-131071.

        Returns:
            int: Byte value 0-255.

        Raises:
            ValueError: If the address is out of range.
        """
        if not 0 <= address < self.SIZE:
            raise ValueError("address out of range")
        return self._read_raw(address, 1)[0]

    def write_byte(self, address, value):
        """Write one byte (WRITE, 0x02); complete when CS is released.

        Args:
            address: 0-131071.
            value: Byte value 0-255.

        Raises:
            ValueError: If the address is out of range.
        """
        if not 0 <= address < self.SIZE:
            raise ValueError("address out of range")
        self._write_raw(address, bytes([value & 0xFF]))


class SRAM23X1024Full(SRAM23X1024Minimal):
    """23X1024 full interface — extends minimal with block access, operating modes and I/O reset.

    read()/write()/fill() have linear-address semantics in every mode: the
    driver splits transfers into transactions of at most max_chunk data bytes
    and, in Page/Byte mode, at the 32-byte page / single-byte boundaries the
    hardware would otherwise wrap or truncate at. Ranges that would leave the
    array raise ValueError instead of silently wrapping around.

    Args:
        connection: SPI Connection (Mode 0, MSB first, CS managed by the connection).
    """

    MODE_BYTE = 0x00
    MODE_PAGE = 0x80
    MODE_SEQUENTIAL = 0x40

    _PAGE_SIZE = 32
    _MAX_CHUNK_LIMIT = 256

    def __init__(self, connection):
        self._mode = self.MODE_SEQUENTIAL
        self._max_chunk = 256
        super().__init__(connection)

    @property
    def max_chunk(self):
        """int: Maximum data bytes per bus transaction (1-256, default 256)."""
        return self._max_chunk

    @max_chunk.setter
    def max_chunk(self, n):
        if not 1 <= n <= self._MAX_CHUNK_LIMIT:
            raise ValueError("max_chunk must be 1-256")
        self._max_chunk = n

    def _chunk_len(self, address, remaining):
        n = min(remaining, self._max_chunk)
        if self._mode == self.MODE_BYTE:
            return 1
        if self._mode == self.MODE_PAGE:
            n = min(n, self._PAGE_SIZE - (address % self._PAGE_SIZE))
        return n

    def _check_range(self, address, length):
        if address < 0 or length < 0 or address + length > self.SIZE:
            raise ValueError("range leaves the 128 KiB array")

    def read(self, address, length):
        """Block read of ``length`` bytes starting at ``address``.

        Args:
            address: Start address; address + length must be <= 131072.
            length: Number of bytes.

        Returns:
            bytes: The data read.
        """
        self._check_range(address, length)
        out = bytearray()
        while length > 0:
            n = self._chunk_len(address, length)
            out += self._read_raw(address, n)
            address += n
            length -= n
        return bytes(out)

    def write(self, address, data):
        """Block write of ``data`` starting at ``address`` (same range rule as read()).

        Args:
            address: Start address.
            data: Bytes to write.
        """
        data = bytes(data)
        self._check_range(address, len(data))
        pos = 0
        while pos < len(data):
            n = self._chunk_len(address, len(data) - pos)
            self._write_raw(address, data[pos:pos + n])
            address += n
            pos += n

    def fill(self, address, length, value=0):
        """Write ``value`` over ``length`` bytes starting at ``address`` (chunked).

        Args:
            address: Start address.
            length: Number of bytes.
            value: Byte value, default 0.
        """
        self._check_range(address, length)
        block = bytes([value & 0xFF]) * self._PAGE_SIZE
        while length > 0:
            n = min(self._chunk_len(address, length), len(block))
            self._write_raw(address, block[:n])
            address += n
            length -= n

    def size(self):
        """Array size in bytes.

        Returns:
            int: 131072.
        """
        return self.SIZE

    def set_mode(self, mode):
        """Select the operating mode (WRMR) and verify it with RDMR.

        Args:
            mode: MODE_BYTE, MODE_PAGE or MODE_SEQUENTIAL.

        Raises:
            ValueError: For any other value.
            OSError: If the read-back does not match.
        """
        if mode not in (self.MODE_BYTE, self.MODE_PAGE, self.MODE_SEQUENTIAL):
            raise ValueError("mode must be MODE_BYTE, MODE_PAGE or MODE_SEQUENTIAL")
        self._write_mode(mode)
        if self._read_mode() != mode:
            raise OSError("23X1024 MODE register read-back mismatch")
        self._mode = mode

    def get_mode(self):
        """Read the operating mode (RDMR bits 7:6).

        Returns:
            int: One of MODE_BYTE, MODE_PAGE, MODE_SEQUENTIAL (0xC0 if reserved).
        """
        return self._read_mode() & self._MODE_MASK

    def reset_io(self):
        """Send RSTIO (0xFF) to return the chip to SPI mode after a stray EDIO/EQIO.

        Resynchronises the cached mode with the chip (re-reads RDMR).
        """
        self._connection.write(bytes([self._CMD_RSTIO]))
        self._mode = self.get_mode()
