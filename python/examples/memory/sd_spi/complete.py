#!/usr/bin/env python3
"""Complete example for the SD card in SPI mode — every method of SDSPIFull (Python/Linux SPI)."""

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


def main():
    cs = LinuxOutputPin(GPIOCHIP, CS_LINE, initial_high=True)
    conn = SPIConnection(SPI_BUS, SPI_DEVICE, mode=0, max_speed_hz=400_000, cs_pin=cs)
    sd = SDSPIFull(conn)                                                              # Create SD driver and init the card, (connection, speed_hz=12000000, crc=True) → SDSPIFull

    print("type:", sd.card_type())                                                    # Card generation, () → int
                                                                                      # 1 = SDv1, 2 = SDv2, 3 = SDHC
    info = sd.info()                                                                  # Geometry and capabilities, () → dict
                                                                                      # block size, count, erase unit, name from the CID
    print(info)
    print("CID:", sd.cid().hex())                                                     # Read CID register, () → bytes
                                                                                      # manufacturer, name, revision, serial, date
    print("CSD:", sd.csd().hex())                                                     # CSD register read at init, () → bytes
    print("OCR: {:08X}".format(sd.ocr()))                                             # Read OCR, () → int
                                                                                      # voltage window and CCS (block addressing) bit
    print("present:", sd.is_present())                                                # Card responds, () → bool

    # Use the last 8 blocks of the card as scratch, restoring their contents.
    scratch = sd.block_count() - 8
    saved = sd.read(scratch, 8)                                                       # Read blocks, (block, count) → bytes
                                                                                      # CMD18 multi-block read for count > 1
    pattern = bytes((i * 7 + 3) & 0xFF for i in range(8 * 512))
    sd.write(scratch, pattern)                                                        # Write blocks, (block, data) → None
                                                                                      # CMD25 multi-block write with stop token
    sd.sync()                                                                         # Wait until programming is done, () → None
    print("roundtrip:", "ok" if sd.read(scratch, 8) == pattern else "MISMATCH")

    sd.set_crc(False)                                                                 # Disable CRC checking, (enabled) → None
                                                                                      # CMD59 off; data CRC16 is no longer verified
    sd.set_crc(True)
    print("set_speed:", sd.set_speed(6_000_000))                                      # Change SCK frequency, (hz) → bool
                                                                                      # sets the spidev clock
    sd.erase(scratch, 8)                                                              # Erase blocks, (block, count) → None
                                                                                      # CMD32/33/38; contents afterwards are card dependent
    sd.write(scratch, saved)                                                          # restore the original contents
    print("done")


if __name__ == "__main__":
    main()
