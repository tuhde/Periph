#!/usr/bin/env python3
"""Minimal example for the 23X1024 1-Mbit serial SRAM (Python/Linux SPI)."""

import os
import sys
import time

# Allow the example to import periph from the repo root when run directly.
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(REPO, "python"))

from periph.connection.spi_linux import SPIConnection      # Create SPI connection, (bus_num, device_num, mode=0, max_speed_hz=1_000_000) → SPIConnection
from periph.chips.memory._23x1024 import SRAM23X1024Minimal  # Create 23X1024 driver, (connection) → SRAM23X1024Minimal


def main():
    bus = int(os.environ.get("SPI_BUS", "0"))
    device = int(os.environ.get("SPI_DEVICE", "0"))

    conn = SPIConnection(bus, device, mode=0, max_speed_hz=8_000_000)               # Create SPI connection, (bus_num, device_num, mode=0, max_speed_hz=8_000_000) → SPIConnection
    sram = SRAM23X1024Minimal(conn)                                                  # Create 23X1024 driver, (connection) → SRAM23X1024Minimal

    counter = 0
    while True:
        sram.write_byte(0x00000, counter)                                            # Write one byte, (address 0–131071, value 0–255) → None
        value = sram.read_byte(0x00000)                                              # Read one byte, (address 0–131071) → int
        print("wrote 0x{:02X}, read 0x{:02X}".format(counter, value))
        counter = (counter + 1) & 0xFF
        time.sleep(0.5)


if __name__ == "__main__":
    main()
