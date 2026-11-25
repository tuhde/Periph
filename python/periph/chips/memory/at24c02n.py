import time


class AT24C02NMinimal:
    """AT24C02N 2 Kbit (256 B) I2C EEPROM — minimal interface.

    Self-timed writes (5 ms max) are completed by ACK polling: after every
    write the driver polls the device address until the chip answers again,
    so a returned write is always committed. The chip has no configuration
    registers; it is ready after power-up.

    Memory layout:
        0x00-0x00FF — 256 bytes, 8-bit address, big-endian on the wire,
        8 pages of 8 bytes

    Default configuration (nothing to configure):
        - The full 256 B is addressable as raw bytes
        - write_byte() returns after the write cycle has finished
        - Writes are ignored while the WP pin is high (hardware only; the
          driver cannot detect it — verify with a read-back if WP is wired)

    Args:
        connection: RegisterConnection (I²C or SMBus) with reg_bytes=1, pointing
            at the device (address 0x50-0x57, set by pins A2:A0).
    """

    _SIZE = 0x0100
    _PAGE = 8
    _WRITE_TIMEOUT_MS = 10     # tWR = 5 ms max, plus margin

    def __init__(self, connection):
        self._connection = connection

    def _check_address(self, address):
        if address < 0 or address >= self._SIZE:
            raise ValueError('address out of range 0-255')

    def _wait_ready(self, timeout_ms):
        """ACK-poll: an address-only write is acknowledged once the write cycle has ended."""
        deadline = time.time() + timeout_ms / 1000.0
        while True:
            try:
                self._connection.write_reg(0, b'')
                return True
            except OSError:
                pass
            if time.time() >= deadline:
                return False
            time.sleep(0.0005)

    def _commit(self):
        if not self._wait_ready(self._WRITE_TIMEOUT_MS):
            raise OSError('AT24C02N write cycle did not complete')

    def read_byte(self, address):
        """Read a single byte (random read).

        Args:
            address: Memory address 0-255.

        Returns:
            int: Byte value 0-255.

        Raises:
            ValueError: If the address is outside 0-255.
        """
        self._check_address(address)
        return self._connection.read_reg(address, 1)[0]

    def write_byte(self, address, value):
        """Write a single byte, then ACK-poll until the write cycle completes.

        Args:
            address: Memory address 0-255.
            value:   Byte value 0-255.

        Raises:
            ValueError: If the address is outside 0-255.
            OSError: If the chip does not answer again within the write-cycle timeout.
        """
        self._check_address(address)
        self._connection.write_reg(address, value & 0xFF)
        self._commit()


class AT24C02NFull(AT24C02NMinimal):
    """AT24C02N full interface — extends minimal with block access and page writes.

    Adds sequential read, page-boundary-aware write, single page write, range
    fill, array and page size and explicit ACK polling. Writes are split at
    8-byte page boundaries (a write crossing a boundary would wrap inside the
    page and corrupt data); ranges that would run past 0x00FF are rejected
    instead of wrapping to 0x0000 as the hardware would do.

    Args:
        connection: RegisterConnection (I²C or SMBus) with reg_bytes=1, pointing
            at the device (address 0x50-0x57).
    """

    def __init__(self, connection):
        super().__init__(connection)
        self._max_chunk = 30

    @property
    def max_chunk(self):
        """int: Maximum data bytes per bus transaction (default 30).

        Arduino Wire limits a transfer to 32 bytes including the 2 address
        bytes, hence the default of 30. Must be at least 1.
        """
        return self._max_chunk

    @max_chunk.setter
    def max_chunk(self, value):
        if value < 1:
            raise ValueError('max_chunk must be >= 1')
        self._max_chunk = value

    def _check_range(self, address, length):
        if length < 0 or address < 0 or address + length > self._SIZE:
            raise ValueError('range outside 0-255')

    def size(self):
        """Return the array size in bytes.

        Returns:
            int: 256.
        """
        return self._SIZE

    def page_size(self):
        """Return the write page size in bytes.

        Returns:
            int: 8.
        """
        return self._PAGE

    def wait_ready(self, timeout_ms):
        """ACK-poll until the chip answers its device address again.

        Args:
            timeout_ms: Maximum time to poll in milliseconds.

        Returns:
            bool: True if the chip responded within the timeout.
        """
        return self._wait_ready(timeout_ms)

    def read(self, address, length):
        """Sequential read of `length` bytes starting at `address`.

        Split into transactions of at most `max_chunk` bytes.

        Args:
            address: Starting address 0-255.
            length:  Number of bytes to read.

        Returns:
            bytes: `length` bytes from the device.

        Raises:
            ValueError: If address…address+length is not inside 0x00-0x00FF.
        """
        self._check_range(address, length)
        out = bytearray()
        while length > 0:
            n = self._max_chunk if length > self._max_chunk else length
            out += self._connection.read_reg(address, n)
            address += n
            length -= n
        return bytes(out)

    def write_page(self, address, data):
        """Write one page (or part of it) in a single transaction, then ACK-poll.

        Args:
            address: Starting address 0-255.
            data:    1-8 bytes, all inside the same 8-byte page.

        Raises:
            ValueError: If the data crosses a page boundary, is longer than a page,
                or runs outside 0x00-0x00FF.
            OSError: If the write cycle does not complete.
        """
        data = bytes(data)
        self._check_range(address, len(data))
        if len(data) > self._PAGE or (address % self._PAGE) + len(data) > self._PAGE:
            raise ValueError('write crosses a page boundary')
        if not data:
            return
        self._connection.write_reg(address, data)
        self._commit()

    def write(self, address, data):
        """Write `data` starting at `address`, split at page boundaries.

        Each chunk is at most `max_chunk` bytes and never crosses a 32-byte
        page boundary; the write cycle is ACK-polled after every chunk.

        Args:
            address: Starting address 0-255.
            data:    Bytes to write.

        Raises:
            ValueError: If address…address+len(data) is not inside 0x00-0x00FF.
            OSError: If a write cycle does not complete.
        """
        data = bytes(data)
        self._check_range(address, len(data))
        offset = 0
        while offset < len(data):
            room = self._PAGE - ((address + offset) % self._PAGE)
            n = min(room, self._max_chunk, len(data) - offset)
            self._connection.write_reg(address + offset, data[offset:offset + n])
            self._commit()
            offset += n

    def fill(self, address, length, value=0):
        """Write `value` over a range, page by page.

        Args:
            address: Starting address 0-255.
            length:  Number of bytes to fill.
            value:   Byte value 0-255 (default 0).

        Raises:
            ValueError: If address…address+length is not inside 0x00-0x00FF.
            OSError: If a write cycle does not complete.
        """
        self._check_range(address, length)
        value &= 0xFF
        done = 0
        while done < length:
            room = self._PAGE - ((address + done) % self._PAGE)
            n = min(room, self._max_chunk, length - done)
            self._connection.write_reg(address + done, bytes([value]) * n)
            self._commit()
            done += n
