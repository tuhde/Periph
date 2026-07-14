import time


class EEPROM24AA02UIDMinimal:
    """24AA02UID 2K I2C EEPROM with 32-bit unique serial number — minimal interface.

    Provides read access to the chip's unique 32-bit serial number and basic
    single-byte read/write access to the user EEPROM. The chip has no
    configuration registers; it is ready for use immediately after power-on.

    Memory layout:
        0x00-0x7F — 128 bytes general-purpose user EEPROM (R/W)
        0x80-0xF9 — reserved, read-only
        0xFA      — manufacturer code (always 0x29, Microchip)
        0xFB      — device code (always 0x41)
        0xFC-0xFF — 32-bit unique serial number, MSB first

    Default configuration (no registers to configure):
        - User EEPROM is read/written as raw bytes (no interpretation)
        - write_byte() waits for the internal write cycle to finish via
          ACK polling before returning (max 5 ms)
        - All addresses 0x80-0xFF are write-protected; writes are silently
          ignored by the chip. Drivers accept the call but the data is not
          retained.

    Args:
        transport: Configured I2C transport pointing at the device (address 0x50).
    """

    _ADDR_UID_BASE    = 0xFC
    _ADDR_MFR_CODE    = 0xFA
    _ADDR_DEV_CODE    = 0xFB
    _USER_EEPROM_END  = 0x7F
    _WRITE_CYCLE_MS   = 5

    def __init__(self, transport):
        self._transport = transport

    def _read_byte(self, address):
        return self._transport.write_read(bytes([address & 0xFF]), 1)[0]

    def _read_bytes(self, address, length):
        return self._transport.write_read(bytes([address & 0xFF]), length)

    def _write_byte(self, address, value):
        self._transport.write(bytes([address & 0xFF, value & 0xFF]))

    def _ack_poll(self):
        try:
            sleep_ms = time.sleep_ms
        except AttributeError:
            sleep_ms = lambda ms: time.sleep(ms / 1000.0)
        for _ in range(20):
            try:
                self._transport.write_read(bytes([0x00]), 1)
                return
            except OSError:
                sleep_ms(1)

    def read_uid(self):
        """Read the chip's factory-programmed 32-bit unique serial number.

        Returns:
            bytes: 4-byte UID, MSB first (0xFC, 0xFD, 0xFE, 0xFF).
        """
        return self._read_bytes(self._ADDR_UID_BASE, 4)

    def read_byte(self, address):
        """Read a single byte from user EEPROM at 0x00-0x7F.

        Args:
            address: Memory address 0-127.

        Returns:
            int: Byte value 0-255.
        """
        return self._read_byte(address)

    def write_byte(self, address, value):
        """Write a single byte to user EEPROM at 0x00-0x7F and wait for completion.

        Sends the byte, then ACK-polls the chip until the internal write
        cycle completes (max 5 ms). Writes to 0x80-0xFF are accepted by the
        device but silently ignored (write-protected region).

        Args:
            address: Memory address 0-255.
            value:   Byte value 0-255.
        """
        self._write_byte(address, value)
        self._ack_poll()


class EEPROM24AA02UIDFull(EEPROM24AA02UIDMinimal):
    """24AA02UID full interface — extends minimal with multi-byte read/write.

    Adds sequential read, raw page write (8-byte page), arbitrary-length
    write that automatically crosses page boundaries, and accessors for
    the manufacturer and device codes in the upper (read-only) block.

    Args:
        transport: Configured I2C transport pointing at the device (address 0x50).
    """

    _PAGE_SIZE = 8

    def read(self, address, length):
        """Sequential read of `length` bytes starting at `address`.

        The internal address pointer auto-increments; reads may cross any
        boundary and wrap at the end of the 256-byte address space.

        Args:
            address: Starting address 0-255.
            length:  Number of bytes to read.

        Returns:
            bytes: `length` bytes from the device.
        """
        return self._read_bytes(address, length)

    def write_page(self, address, data):
        """Write up to 8 bytes within a single 8-byte page.

        The caller is responsible for ensuring all bytes lie within the
        same page. Bytes that would overflow the page boundary wrap to the
        start of the same page (FIFO overwrite) — use write() to handle
        boundaries automatically.

        Args:
            address: Start address within an 8-byte page (0, 8, 16, …).
            data:    Bytes to write (1 to 8 bytes).
        """
        buf = bytes([address & 0xFF]) + bytes(data)
        self._transport.write(buf)
        self._ack_poll()

    def write(self, address, data):
        """Write an arbitrary-length buffer, splitting at 8-byte page boundaries.

        Automatically splits the write into page-aligned chunks and waits
        for the write cycle of each chunk before continuing.

        Args:
            address: Starting address 0-255.
            data:    Bytes to write.
        """
        offset = 0
        remaining = len(data)
        current = address
        while remaining > 0:
            page_offset = current & (self._PAGE_SIZE - 1)
            chunk = self._PAGE_SIZE - page_offset
            if chunk > remaining:
                chunk = remaining
            self.write_page(current, data[offset:offset + chunk])
            offset += chunk
            current += chunk
            remaining -= chunk

    def read_manufacturer_code(self):
        """Read the manufacturer code at 0xFA.

        Returns:
            int: Manufacturer code; expect 0x29 (Microchip).
        """
        return self._read_byte(self._ADDR_MFR_CODE)

    def read_device_code(self):
        """Read the device code at 0xFB.

        Returns:
            int: Device code; expect 0x41 (I2C 2-Kbit EEPROM).
        """
        return self._read_byte(self._ADDR_DEV_CODE)
