#!/usr/bin/env python3
"""Minimal example for the SD card in SPI mode (Python/Linux SPI, read-only)."""

import os
import sys
import time

# Allow the example to import periph from the repo root when run directly.
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(REPO, "python"))

from periph.connection.spi_linux import SPIConnection      # Create SPI connection, (bus_num, device_num, mode=0, max_speed_hz=1_000_000, cs_pin=None) → SPIConnection
from periph.connection.output_pin import LinuxOutputPin   # Create CS pin, (chip_path, line_offset, initial_high=False) → LinuxOutputPin
from periph.chips.memory.sd_spi import SDSPIMinimal

SPI_BUS = int(os.environ.get("SPI_BUS", "0"))
SPI_DEVICE = int(os.environ.get("SPI_DEVICE", "0"))
GPIOCHIP = os.environ.get("GPIOCHIP", "/dev/gpiochip0")
CS_LINE = int(os.environ.get("CS_LINE", "25"))  # a free GPIO wired to the card's CS


def main():
    cs = LinuxOutputPin(GPIOCHIP, CS_LINE, initial_high=True)                         # Create CS pin, (chip_path, line_offset, initial_high=True) → LinuxOutputPin
    conn = SPIConnection(SPI_BUS, SPI_DEVICE, mode=0, max_speed_hz=400_000, cs_pin=cs)  # Create SPI connection, (bus_num, device_num, mode=0, max_speed_hz=400_000, cs_pin=cs) → SPIConnection
    sd = SDSPIMinimal(conn)                                                           # Create SD driver and init the card, (connection, speed_hz=12000000, crc=False) → SDSPIMinimal

    print("capacity: {} blocks = {} MiB".format(sd.block_count(), sd.block_count() // 2048))  # Capacity in 512-byte blocks, () → int
    while True:
        block = sd.read_block(0)                                                      # Read one 512-byte block, (block) → bytes
        print("block 0:", " ".join("{:02X}".format(b) for b in block[:16]))
        print("boot signature: {:02X}{:02X}".format(block[510], block[511]))
        time.sleep(2)


if __name__ == "__main__":
    main()
