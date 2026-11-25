#!/usr/bin/env python3
"""Demo: an append-only record log on bare NOR flash (Python/Linux SPI)."""

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
import struct

RING = 0xFFC000
SECTORS = 4
SLOT = 32
SLOTS = SECTORS * 4096 // SLOT


def main():
    # --- Bring up the chip ---
    # Plain single-I/O SPI: the driver releases deep power-down, checks the JEDEC ID and
    # waits until the chip is idle. The serial-number area is the factory ESN on parts that have one.
    conn = SPIConnection(SPI_BUS, SPI_DEVICE, mode=0, max_speed_hz=8_000_000)
    flash = MX25L12845EFull(conn)                                                    # Create MX25L12845E driver and check the ID, (connection, auto_erase=False) → MX25L12845EFull
    print("MX25L12845E, {} MiB, serial {}".format(flash.size() // (1024 * 1024), flash.serial_number().hex()))   # Capacity, () → int; serial-number area, () → bytes

    # --- Append records to a ring of four sectors ---
    # 32-byte records (sequence, tick, payload) fill a sector, 128 per sector. A blank slot
    # (all 0xFF) needs no erase, so appending is just a page program; only when the writer
    # enters the next sector is that sector erased first — one erase per 128 records.
    for s in range(SECTORS):
        flash.erase_sector(RING + s * 4096)                                          # Erase one 4 KiB sector, (address) → None
    t0 = time.monotonic()
    erases = 0
    slot = 0
    for seq in range(300):
        if slot % 128 == 0 and seq >= SLOTS:                                         # wrapped into the next sector: make it blank
            flash.erase_sector(RING + (slot // 128) * 4096)
            erases += 1
        record = struct.pack("<II", seq, int((time.monotonic() - t0) * 1000) & 0xFFFFFFFF) + b"\xa5" * 24
        flash.page_program(RING + slot * SLOT, record)                               # Program one record (inside a page), (address, data) → None
        slot = (slot + 1) % SLOTS
    print("appended 300 records in {:.0f} ms, {} sector erases".format((time.monotonic() - t0) * 1000, erases))

    # --- Verify by scanning the ring ---
    # Every slot must carry a valid sequence number and the 0xA5 payload.
    good = 0
    newest = 0
    for s in range(SLOTS):
        rec = flash.read_bytes(RING + s * SLOT, SLOT)                                # Read bytes, (address, length) → bytes
        seq = struct.unpack("<I", rec[:4])[0]
        if seq != 0xFFFFFFFF and rec[20] == 0xA5:
            good += 1
            newest = max(newest, seq)
    print("ring holds {} valid records, newest sequence number {}".format(good, newest))

    # --- Leave the chip clean ---
    for s in range(SECTORS):
        flash.erase_sector(RING + s * 4096)
    print("ring erased")


if __name__ == "__main__":
    main()
