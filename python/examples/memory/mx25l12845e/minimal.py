#!/usr/bin/env python3
"""Minimal example for the MX25L12845E NOR flash (Python/Linux SPI)."""

import os
import sys
import time

# Allow the example to import periph from the repo root when run directly.
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(REPO, "python"))

from periph.connection.spi_linux import SPIConnection      # Create SPI connection, (bus_num, device_num, mode=0, max_speed_hz=1_000_000) → SPIConnection
from periph.chips.memory.mx25l12845e import MX25L12845EMinimal

SPI_BUS = int(os.environ.get("SPI_BUS", "0"))
SPI_DEVICE = int(os.environ.get("SPI_DEVICE", "0"))
SCRATCH = 0xFF0000  # all writes and erases stay inside the last 64 KiB of the chip


def main():
    conn = SPIConnection(SPI_BUS, SPI_DEVICE, mode=0, max_speed_hz=8_000_000)        # Create SPI connection, (bus_num, device_num, mode=0, max_speed_hz=8_000_000) → SPIConnection
    flash = MX25L12845EMinimal(conn)                                                 # Create MX25L12845E driver and check the ID, (connection) → MX25L12845EMinimal

    print("JEDEC ID", flash.jedec_id().hex(), "{} MiB".format(flash.size() // (1024 * 1024)))   # Read JEDEC ID, () → bytes; capacity, () → int
    for counter in range(10):
        flash.write_bytes(SCRATCH, bytes([counter]))                                 # Write bytes (erases the sector when needed), (address, data) → None
        value = flash.read_bytes(SCRATCH, 1)[0]                                      # Read bytes, (address, length) → bytes
        print("wrote 0x{:02X}, read 0x{:02X}".format(counter, value))
        time.sleep(0.5)
    flash.erase_sector(SCRATCH)                                                      # Erase one 4 KiB sector, (address) → None
    print("done, scratch sector erased")


if __name__ == "__main__":
    main()
