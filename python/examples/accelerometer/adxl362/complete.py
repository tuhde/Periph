#!/usr/bin/env python3
"""Complete example for the ADXL362 3-axis accelerometer (Python/Linux SPI).

Exercises every method of ADXL362Full.
"""

import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(REPO, "python"))

from periph.connection.spi_linux import SPIConnection      # Create SPI connection, (bus_num, device_num, mode=0, max_speed_hz=1_000_000) → SPIConnection
from periph.chips.accelerometer.adxl362 import ADXL362Full  # Create ADXL362 full driver, (connection) → ADXL362Full


def main():
    bus = int(os.environ.get("SPI_BUS", "0"))
    device = int(os.environ.get("SPI_DEVICE", "0"))

    conn = SPIConnection(bus, device, mode=0, max_speed_hz=8_000_000)               # Create SPI connection, (bus_num, device_num, mode=0, max_speed_hz=8_000_000) → SPIConnection
    sensor = ADXL362Full(conn)                                                        # Create ADXL362 full driver, (connection) → ADXL362Full
                                                                                      # Runs init() — verifies DEVID_AD=0xAD, DEVID_MST=0x1D, PARTID=0xF2 and enters measurement mode

    devad, devmst, partid, revid = sensor.device_id()                                 # Read device IDs, () → (int, int, int, int)
                                                                                      # returns (DEVID_AD, DEVID_MST, PARTID, REVID) raw register bytes
    print("DEVID_AD=0x{:02X} DEVID_MST=0x{:02X} PARTID=0x{:02X} REVID=0x{:02X}".format(
        devad, devmst, partid, revid))                                                # Print formatted IDs, () → None

    sensor.set_range(4)                                                               # Set measurement range, (range_g=4) → None
                                                                                      # sets FILTER_CTL.RANGE=±4 g (1 mg/LSB; here 2 mg/LSB)
    sensor.set_odr(200.0)                                                             # Set output data rate, (odr_hz=200.0) → None
                                                                                      # sets FILTER_CTL.ODR to nearest supported rate
    sensor.set_half_bandwidth(True)                                                   # Set antialiasing bandwidth, (enabled=True) → None
                                                                                      # FILTER_CTL.HALF_BW=1 → bandwidth = ODR/4 (more conservative)
    sensor.set_noise_mode(ADXL362Full.NOISE_LOW)                                      # Set noise mode, (mode=NOISE_LOW=1) → None
                                                                                      # POWER_CTL.LOW_NOISE=01 → low-noise mode (higher current)

    x, y, z = sensor.read()                                                           # Read 12-bit acceleration, () → (float, float, float) g
                                                                                      # burst-reads XDATA_L/H, YDATA_L/H, ZDATA_L/H in one transaction
    print("12-bit: x={:+.3f}  y={:+.3f}  z={:+.3f}".format(x, y, z))                 # Print 12-bit XYZ, () → None

    x8, y8, z8 = sensor.read_8bit()                                                   # Read 8-bit acceleration, () → (float, float, float) g
                                                                                      # burst-reads XDATA/YDATA/ZDATA — lower bus/power cost than read()
    print(" 8-bit: x={:+.3f}  y={:+.3f}  z={:+.3f}".format(x8, y8, z8))              # Print 8-bit XYZ, () → None

    t = sensor.temperature()                                                          # Read temperature, () → float °C
                                                                                      # typical bias=350 LSB @25 °C, sensitivity=0.065 °C/LSB — uncalibrated
    print("temperature: {:.2f} °C".format(t))                                         # Print temperature, () → None

    raw_status = sensor.status()                                                      # Read STATUS register, () → int
                                                                                      # raw byte: DATA_READY, FIFO_READY, FIFO_WATERMARK, FIFO_OVERRUN, ACT, INACT, AWAKE, ERR_USER_REGS
    print("status: 0x{:02X}".format(raw_status))                                      # Print STATUS byte, () → None
    print("awake: {}".format(sensor.awake()))                                         # Check AWAKE bit, () → bool
                                                                                      # true when device is awake per activity/inactivity state
    print("data_ready: {}".format(sensor.data_ready()))                               # Check DATA_READY, () → bool
                                                                                      # true when new sample is available

    print("fifo_entries: {}".format(sensor.fifo_entries()))                           # Read FIFO entry count, () → int
                                                                                      # 10-bit count, 0–512 entries

    sensor.configure_fifo(mode=ADXL362Full.FIFO_STREAM,                               # Configure FIFO, (mode=STREAM=2, store_temp=False, watermark=128) → None
                          store_temp=False, watermark=128)                            # sets FIFO_CONTROL (mode, AH watermark bit, FIFO_TEMP) and FIFO_SAMPLES
    sensor.set_activity_threshold(0.5, referenced=True)                               # Set activity threshold, (threshold_g=0.5, referenced=True) → None
                                                                                      # writes THRESH_ACT_L/H and sets ACT_INACT_CTL.ACT_REF=1
    sensor.set_activity_time(5)                                                       # Set activity time, (samples=5) → None
                                                                                      # TIME_ACT = 5 — number of over-threshold samples required to fire ACT
    sensor.set_inactivity_threshold(0.2, referenced=True)                             # Set inactivity threshold, (threshold_g=0.2, referenced=True) → None
                                                                                      # writes THRESH_INACT_L/H and sets ACT_INACT_CTL.INACT_REF=1
    sensor.set_inactivity_time(30)                                                    # Set inactivity time, (samples=30) → None
                                                                                      # TIME_INACT_L/H = 30 — consecutive under-threshold samples before INACT fires
    sensor.enable_activity_detection(True)                                            # Enable activity detection, (enabled=True) → None
                                                                                      # ACT_INACT_CTL.ACT_EN=1
    sensor.enable_inactivity_detection(True)                                          # Enable inactivity detection, (enabled=True) → None
                                                                                      # ACT_INACT_CTL.INACT_EN=1
    sensor.set_link_loop_mode(ADXL362Full.LINKLOOP_LOOP)                              # Set link/loop mode, (mode=LOOP=3) → None
                                                                                      # ACT_INACT_CTL.LINKLOOP=11 — chip autonomously toggles ACT/INACT

    sensor.set_interrupt(1, ADXL362Full.SOURCE_DATA_READY, True)                      # Map DATA_READY to INT1, (pin=1, source=DATA_READY=0, enabled=True) → None
                                                                                      # sets INTMAP1.DATA_READY=1
    sensor.set_interrupt(2, ADXL362Full.SOURCE_AWAKE, True)                           # Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=True) → None
                                                                                      # sets INTMAP2.AWAKE=1
    sensor.set_interrupt_polarity(1, active_low=True)                                 # Set INT1 active-low, (pin=1, active_low=True) → None
                                                                                      # INTMAP1.INT_LOW=1

    sensor.self_test(True)                                                            # Enable self-test, (enabled=True) → None
                                                                                      # SELF_TEST.ST=1 — apply electrostatic force on all three axes
    time.sleep(0.5)                                                                   # Wait 4/ODR + slack, () → None
    sensor.self_test(False)                                                           # Disable self-test, (enabled=False) → None
                                                                                      # SELF_TEST.ST=0

    sensor.soft_reset()                                                               # Soft-reset the chip, () → None
                                                                                      # writes 0x52 to SOFT_RESET; all registers return to defaults — caller must re-init


if __name__ == "__main__":
    main()