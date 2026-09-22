#!/usr/bin/env python3
"""Minimal example for the ADXL362 3-axis accelerometer (Python/Linux SPI)."""

import os
import sys
import time

# Allow the example to import periph from the repo root when run directly.
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(REPO, "python"))

from periph.connection.spi_linux import SPIConnection      # Create SPI connection, (bus_num, device_num, mode=0, max_speed_hz=1_000_000) → SPIConnection
from periph.chips.accelerometer.adxl362 import ADXL362Minimal  # Create ADXL362 driver, (connection) → ADXL362Minimal


def main():
    bus = int(os.environ.get("SPI_BUS", "0"))
    device = int(os.environ.get("SPI_DEVICE", "0"))

    conn = SPIConnection(bus, device, mode=0, max_speed_hz=8_000_000)               # Create SPI connection, (bus_num, device_num, mode=0, max_speed_hz=8_000_000) → SPIConnection
    sensor = ADXL362Minimal(conn)                                                    # Create ADXL362 driver, (connection) → ADXL362Minimal

    while True:
        x, y, z = sensor.read()                                                      # Read 3-axis acceleration, () → (float, float, float) g
        print("x={:+.3f} g  y={:+.3f} g  z={:+.3f} g".format(x, y, z))                # Print formatted XYZ, () → None
        time.sleep(0.1)


if __name__ == "__main__":
    main()