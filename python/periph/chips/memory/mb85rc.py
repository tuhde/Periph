class MB85RCMinimal:
    """MB85RC256V 256 Kbit (32 KiB) I2C FRAM — minimal interface.

    Ferroelectric RAM: non-volatile like EEPROM, but every write completes at
    bus speed — no write cycle, no page buffer, no ACK polling, and at least
    10^12 write cycles per byte. The chip has no configuration registers; it
    is ready for use immediately after power-on.

    Memory layout:
        0x0000-0x7FFF — 32,768 bytes of FRAM, 15-bit address, big-endian on the wire

    Default configuration (nothing to configure):
        - The full 32 KiB is addressable as raw bytes
        - write_byte() returns as soon as the transfer completes
        - Writes are ignored by the chip while the WP pin is high (hardware only;
          the driver cannot detect it)

    Args:
        connection: RegisterConnection (I²C or SMBus) with reg_bytes=2, pointing
            at the device (address 0x50-0x57, set by pins A2:A0).
    """

    _SIZE = 0x8000

    def __init__(self, connection):
        self._connection = connection

    def _check_address(self, address):
        if address < 0 or address >= self._SIZE:
            raise ValueError('address out of range 0-32767')

    def read_byte(self, address):
        """Read a single byte (random read).

        Args:
            address: Memory address 0-32767.

        Returns:
            int: Byte value 0-255.

        Raises:
            ValueError: If the address is outside 0-32767.
        """
        self._check_address(address)
        return self._connection.read_reg(address, 1)[0]

    def write_byte(self, address, value):
        """Write a single byte (byte write); returns when the transfer completes.

        There is no write cycle and no polling — data is committed right after
        the byte is acknowledged.

        Args:
            address: Memory address 0-32767.
            value:   Byte value 0-255.

        Raises:
            ValueError: If the address is outside 0-32767.
        """
        self._check_address(address)
        self._connection.write_reg(address, value & 0xFF)


class MB85RCFull(MB85RCMinimal):
    """MB85RC256V full interface — extends minimal with block access and Device ID.

    Adds sequential read/write split into bus-sized chunks, range fill,
    the array size, and the 3-byte Device ID read through the reserved
    slave address 0x7C. Ranges that would run past 0x7FFF are rejected
    instead of silently wrapping to 0x0000 as the hardware would do.

    Args:
        connection: RegisterConnection (I²C or SMBus) with reg_bytes=2, pointing
            at the device (address 0x50-0x57).
    """

    _DEVICE_ID_BASE = 0xA0
    _MANUFACTURER = 0x00A
    _DENSITY = 0x5
    _PRODUCT = 0x10

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
            raise ValueError('range outside 0-32767')

    def size(self):
        """Return the array size in bytes.

        Returns:
            int: 32768.
        """
        return self._SIZE

    def read(self, address, length):
        """Sequential read of `length` bytes starting at `address`.

        Split into transactions of at most `max_chunk` bytes.

        Args:
            address: Starting address 0-32767.
            length:  Number of bytes to read.

        Returns:
            bytes: `length` bytes from the device.

        Raises:
            ValueError: If address…address+length is not inside 0x0000-0x7FFF.
        """
        self._check_range(address, length)
        out = bytearray()
        while length > 0:
            n = self._max_chunk if length > self._max_chunk else length
            out += self._connection.read_reg(address, n)
            address += n
            length -= n
        return bytes(out)

    def write(self, address, data):
        """Sequential write of `data` starting at `address`.

        Split into transactions of at most `max_chunk` bytes; each chunk is a
        fresh transaction with its own address bytes. No delay between chunks.

        Args:
            address: Starting address 0-32767.
            data:    Bytes to write.

        Raises:
            ValueError: If address…address+len(data) is not inside 0x0000-0x7FFF.
        """
        data = bytes(data)
        self._check_range(address, len(data))
        offset = 0
        while offset < len(data):
            chunk = data[offset:offset + self._max_chunk]
            self._connection.write_reg(address + offset, chunk)
            offset += len(chunk)

    def fill(self, address, length, value=0):
        """Write `value` over a range, in `max_chunk`-sized transactions.

        Args:
            address: Starting address 0-32767.
            length:  Number of bytes to fill.
            value:   Byte value 0-255 (default 0).

        Raises:
            ValueError: If address…address+length is not inside 0x0000-0x7FFF.
        """
        self._check_range(address, length)
        value &= 0xFF
        while length > 0:
            n = self._max_chunk if length > self._max_chunk else length
            self._connection.write_reg(address, bytes([value]) * n)
            address += n
            length -= n

    def read_device_id(self, id_connection, address=0x50):
        """Read the 3-byte Device ID via the reserved slave address 0x7C.

        The write phase sends the device address word (0xA0 + A2A1A0 * 2),
        then a repeated START and a 3-byte read. Linux i2c-dev may refuse
        addresses 0x78-0x7F, in which case this raises.

        Args:
            id_connection: RegisterConnection (reg_bytes=1) bound to I2C address 0x7C.
            address: 7-bit address of the FRAM (0x50-0x57), used to build the
                device address word (default 0x50).

        Returns:
            tuple: (manufacturer, density, product) — (0x00A, 0x5, 0x10) for the MB85RC256V.
        """
        word = self._DEVICE_ID_BASE + ((address & 0x07) << 1)
        raw = id_connection.read_reg(word, 3)
        manufacturer = (raw[0] << 4) | (raw[1] >> 4)
        density = raw[1] & 0x0F
        return (manufacturer, density, raw[2])
