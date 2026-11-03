#!/usr/bin/env python3
"""Complete example for the 23X1024 1-Mbit serial SRAM (Python/Linux SPI)."""

import os
import sys
import time

# Allow the example to import periph from the repo root when run directly.
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(REPO, "python"))

from periph.connection.spi_linux import SPIConnection      # Create SPI connection, (bus_num, device_num, mode=0, max_speed_hz=1_000_000) → SPIConnection
from periph.chips.memory._23x1024 import SRAM23X1024Full  # Create 23X1024 driver, (connection) → SRAM23X1024Full


def main():
    bus = int(os.environ.get("SPI_BUS", "0"))
    device = int(os.environ.get("SPI_DEVICE", "0"))

    conn = SPIConnection(bus, device, mode=0, max_speed_hz=8_000_000)               # Create SPI connection, (bus_num, device_num, mode=0, max_speed_hz=8_000_000) → SPIConnection
    sram = SRAM23X1024Full(conn)                                                     # Create 23X1024 Full driver, (connection) → SRAM23X1024Full
                                                                                     # writes MODE=0x40 (Sequential) and verifies it with RDMR

    print("size: {} bytes".format(sram.size()))                                      # Array size, () → int bytes
                                                                                     # always 131072

    sram.write_byte(0x00010, 0xA5)                                                   # Write one byte, (address 0–131071, value 0–255) → None
                                                                                     # WRITE 0x02 + 24-bit address + data
    print("byte @0x10: 0x{:02X}".format(sram.read_byte(0x00010)))                    # Read one byte, (address 0–131071) → int
                                                                                     # READ 0x03 + 24-bit address

    pattern = bytes(range(256)) + bytes(range(44))
    sram.write(0x00100, pattern)                                                     # Block write, (address, data) → None
                                                                                     # split into transactions of at most max_chunk bytes
    readback = sram.read(0x00100, len(pattern))                                      # Block read, (address, length) → bytes
                                                                                     # sequential read across the chunk boundary
    print("block of 300:", "match" if readback == pattern else "MISMATCH")

    sram.fill(0x00200, 64, 0xFF)                                                     # Fill a range, (address, length, value=0) → None
                                                                                     # writes value over the range, chunked
    print("fill @0x200: 0x{:02X}".format(sram.read_byte(0x00200)))

    sram.max_chunk = 64                                                              # Limit bytes per transaction, (n 1–256) → None
                                                                                     # smaller frames for bus layers with small buffers
    print("max_chunk:", sram.max_chunk)
    sram.max_chunk = 256                                                             # Limit bytes per transaction, (n 1–256) → None

    sram.set_mode(SRAM23X1024Full.MODE_PAGE)                                         # Select operating mode, (mode=MODE_PAGE=0x80) → None
                                                                                     # WRMR then RDMR check; accesses now wrap within 32-byte pages
    print("mode: 0x{:02X}".format(sram.get_mode()))                                  # Read operating mode, () → int
                                                                                     # RDMR bits 7:6
    sram.write(0x00110, pattern[:64])                                                # Block write, (address, data) → None
                                                                                     # the driver splits at page boundaries, so the data stays linear
    print("page mode 64 bytes:", "match" if sram.read(0x00110, 64) == pattern[:64] else "MISMATCH")  # Block read, (address, length) → bytes

    sram.set_mode(SRAM23X1024Full.MODE_SEQUENTIAL)                                   # Select operating mode, (mode=MODE_SEQUENTIAL=0x40) → None
    sram.reset_io()                                                                  # Return to SPI mode, () → None
                                                                                     # sends RSTIO (0xFF); harmless while already in SPI mode
    conn.close()


if __name__ == "__main__":
    main()
