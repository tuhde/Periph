#!/usr/bin/env python3
"""Demo example for the 23X1024 1-Mbit serial SRAM (Python/Linux SPI)."""

import math
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

    # --- Store a 256-entry lookup table in external SRAM ---
    # A sine table is the kind of data a microcontroller with little RAM
    # keeps outside: written once, then read back entry by entry.
    table = bytes(int(127.5 + 127.5 * math.sin(2 * math.pi * i / 256)) for i in range(256))
    sram.write(0x00000, table)                                                       # Block write, (address, data) → None
    check = sram.read(0x00000, len(table))                                           # Block read, (address, length) → bytes
    print("sine table: {} (sin(64/256 turn) = {})".format(
        "ok" if check == table else "MISMATCH", sram.read_byte(64)))                 # Read one byte, (address) → int

    # --- Wipe the whole array and spot-check it ---
    # Zero write time means the 128 KiB fill runs at SPI speed with no
    # waiting; the first, middle and last byte prove the full range.
    sram.fill(0x00000, sram.size(), 0xA5)                                            # Fill a range, (address, length, value=0) → None
    print("fill spot check: 0x{:02X} 0x{:02X} 0x{:02X}".format(
        sram.read_byte(0x00000), sram.read_byte(0x10000), sram.read_byte(0x1FFFF)))  # Read one byte, (address) → int

    # --- Page mode: a write that crosses a 32-byte page boundary ---
    # The hardware would wrap inside the page; the driver splits the
    # transfer so the 64 bytes starting at address 16 still read back in order.
    sram.set_mode(SRAM23X1024Full.MODE_PAGE)                                         # Select operating mode, (mode=MODE_PAGE=0x80) → None
    ramp = bytes(0x40 + i for i in range(64))
    sram.write(16, ramp)                                                             # Block write, (address, data) → None
    ramp_back = sram.read(16, len(ramp))                                             # Block read, (address, length) → bytes
    print("page-crossing write:", "ok" if ramp == ramp_back else "MISMATCH")
    sram.set_mode(SRAM23X1024Full.MODE_SEQUENTIAL)                                   # Select operating mode, (mode=MODE_SEQUENTIAL=0x40) → None

    # --- Measure throughput for a 4 KiB transfer ---
    # Sequential mode streams the block in 256-byte transactions;
    # time.monotonic() times them.
    block = bytes(i & 0xFF for i in range(4096))
    t0 = time.monotonic()
    sram.write(0x01000, block)                                                       # Block write, (address, data) → None
    t1 = time.monotonic()
    sram.read(0x01000, len(block))                                                   # Block read, (address, length) → bytes
    t2 = time.monotonic()
    print("4 KiB write: {:.1f} ms, read: {:.1f} ms".format((t1 - t0) * 1000, (t2 - t1) * 1000))
    conn.close()


if __name__ == "__main__":
    main()
