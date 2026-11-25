#!/usr/bin/env python3
"""Demo: log samples to a raw block range of an SD card (filesystem-free data logger), Python/Linux SPI."""

import os
import sys
import time

# Allow the example to import periph from the repo root when run directly.
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(REPO, "python"))

from periph.connection.spi_linux import SPIConnection      # Create SPI connection, (bus_num, device_num, mode=0, max_speed_hz=1_000_000, cs_pin=None) → SPIConnection
from periph.connection.output_pin import LinuxOutputPin   # Create CS pin, (chip_path, line_offset, initial_high=False) → LinuxOutputPin
from periph.chips.memory.sd_spi import SDSPIFull

SPI_BUS = int(os.environ.get("SPI_BUS", "0"))
SPI_DEVICE = int(os.environ.get("SPI_DEVICE", "0"))
GPIOCHIP = os.environ.get("GPIOCHIP", "/dev/gpiochip0")
CS_LINE = int(os.environ.get("CS_LINE", "25"))  # a free GPIO wired to the card's CS
import struct

BLOCKS = 64


def main():
    # --- Bring up the card ---
    # The card starts in SD mode; the driver sends 80 clocks with CS high, CMD0 with CS
    # low to enter SPI mode, then negotiates the card type. SCK runs at 400 kHz during
    # init and is raised to 12 MHz afterwards.
    cs = LinuxOutputPin(GPIOCHIP, CS_LINE, initial_high=True)
    conn = SPIConnection(SPI_BUS, SPI_DEVICE, mode=0, max_speed_hz=400_000, cs_pin=cs)
    sd = SDSPIFull(conn)                                                              # Create SD driver and init the card, (connection, speed_hz=12000000, crc=True) → SDSPIFull
    info = sd.info()                                                                  # Geometry and capabilities, () → dict
    print("card {}, {} MiB".format(info["name"], info["block_count"] // 2048))

    # --- Log samples to a raw block range ---
    # Each 512-byte block holds a header (sequence number, tick) and 126 samples.
    # The last blocks of the card are used and restored afterwards, so a card that
    # carries a filesystem keeps its data (only the tail of the volume is touched).
    first = sd.block_count() - BLOCKS
    saved = sd.read(first, BLOCKS)                                                    # Read blocks, (block, count) → bytes
    t0 = time.monotonic()
    for seq in range(BLOCKS):
        words = [seq, int(time.monotonic() * 1000) & 0xFFFFFFFF] + [seq * 1000 + i for i in range(2, 128)]
        sd.write(first + seq, struct.pack("<128I", *words))                           # Write blocks, (block, data) → None
    sd.sync()                                                                         # Wait until programming is done, () → None
    print("wrote {} blocks in {:.0f} ms".format(BLOCKS, (time.monotonic() - t0) * 1000))

    # --- Verify by reading back ---
    # Read every block back and recompute the expected sample values; any single
    # mismatch (or a CRC16 error raised by the driver) is counted.
    bad = 0
    for seq in range(BLOCKS):
        words = struct.unpack("<128I", sd.read(first + seq, 1))                       # Read blocks, (block, count) → bytes
        if words[0] != seq or any(words[i] != seq * 1000 + i for i in range(2, 128)):
            bad += 1
    print("verify: {} bad blocks".format(bad))

    # --- Restore the original contents ---
    sd.write(first, saved)
    sd.sync()
    print("restored")


if __name__ == "__main__":
    main()
