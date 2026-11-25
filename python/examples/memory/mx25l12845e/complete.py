#!/usr/bin/env python3
"""Complete example for the MX25L12845E NOR flash — every method of MX25L12845EFull (Python/Linux SPI)."""

import os
import sys
import time

# Allow the example to import periph from the repo root when run directly.
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(REPO, "python"))

from periph.connection.spi_linux import SPIConnection      # Create SPI connection, (bus_num, device_num, mode=0, max_speed_hz=1_000_000) → SPIConnection
from periph.chips.memory.mx25l12845e import MX25L12845EFull

SPI_BUS = int(os.environ.get("SPI_BUS", "0"))
SPI_DEVICE = int(os.environ.get("SPI_DEVICE", "0"))
SCRATCH = 0xFF0000  # all writes and erases stay inside the last 64 KiB of the chip


def main():
    conn = SPIConnection(SPI_BUS, SPI_DEVICE, mode=0, max_speed_hz=8_000_000)
    flash = MX25L12845EFull(conn)                                                    # Create MX25L12845E driver and check the ID, (connection, auto_erase=False) → MX25L12845EFull

    print("JEDEC", flash.jedec_id().hex(), "RES", hex(flash.electronic_id()), "REMS", flash.device_ids())   # JEDEC ID, () → bytes; RES, () → int; REMS, () → (int, int)
    print("status 0x{:02X}, BP={}, security 0x{:02X}".format(flash.read_status(), flash.protection(), flash.read_security()))   # Status register, () → int; BP3:0, () → int; security register, () → int
    print("ESN:", flash.serial_number().hex())                                       # Read the serial-number OTP area, () → bytes
                                                                                     # the factory ESN when security bit 0 is set, blank (FF) otherwise
    print("SFDP:", flash.read_sfdp(0, 16).hex())                                     # Read the SFDP table, (address, length) → bytes
                                                                                     # starts with the signature "SFDP"

    flash.erase_sector(SCRATCH)                                                      # Erase one 4 KiB sector, (address) → None
                                                                                     # takes 60 ms typ, 300 ms max
    page = bytes(range(256))
    flash.page_program(SCRATCH, page)                                                # Program one page (must not cross a page boundary), (address, data) → None
                                                                                     # 1.4 ms typ for 256 bytes; bits only go 1 -> 0
    print("page program + fast_read:", "ok" if flash.fast_read(SCRATCH, 256) == page else "MISMATCH")   # FAST_READ, (address, length) → bytes
                                                                                     # same data as read_bytes, for clocks above 50 MHz
    flash.erase_block_32k(SCRATCH)                                                   # Erase a 32 KiB block, (address) → None
                                                                                     # 0.5 s typ
    flash.erase_block_64k(SCRATCH)                                                   # Erase a 64 KiB block, (address) → None
                                                                                     # 0.7 s typ
    print("after block erase:", flash.read_bytes(SCRATCH, 16).hex())                 # Read bytes, (address, length) → bytes
    # chip_erase() (80 s typ, wipes all 16 MiB) is deliberately not called here.

    text = b"erase-aware write"
    flash.write_bytes(SCRATCH + 100, text)                                           # Write bytes, erasing the sector only when needed, (address, data) → None
    flash.write_bytes(SCRATCH + 100, text)                                           # the same bytes again: nothing is programmed or erased
    print("text:", flash.read_bytes(SCRATCH + 100, len(text)))

    flash.deep_power_down()                                                          # Enter deep power-down, () → None
                                                                                     # only RDP / RES are accepted afterwards
    flash.release_deep_power_down()                                                  # Leave deep power-down (RDP, waits tRES2), () → None
    print("busy:", flash.is_busy())                                                  # WIP bit, () → bool
    flash.wait_ready(1000)                                                           # Wait for WIP = 0, (timeout_ms) → None

    print(flash.info())                                                              # Geometry and capabilities, () → dict
                                                                                     # 512-byte blocks, 4 KiB erase unit, needs_erase
    # set_protection(), write_status(), program_otp() and lock_otp() change non-volatile state; see the spec before using them.

    flash.erase_sector(SCRATCH)
    print("done")


if __name__ == "__main__":
    main()
