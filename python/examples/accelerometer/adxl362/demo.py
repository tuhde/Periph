#!/usr/bin/env python3
"""Demo for the ADXL362 3-axis accelerometer — ultralow-power motion-activated wake.

Mirrors the datasheet's Autonomous Motion Switch application: configure
activity/inactivity thresholds with referenced (relative) detection,
engage loop mode so the chip autonomously toggles between active and
inactive measurement, map AWAKE to INT2, and enter wake-up mode. The
demo then polls awake() every 200 ms and counts asleep↔awake transitions.

The chip draws ~270 nA during 'asleep' periods — roughly two orders of
magnitude below the ~1.8 µA of the continuous 100 Hz measurement mode
used by the Minimal read() example.
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

    # --- Configure referenced activity/inactivity thresholds ---
    # 0.25 g activity threshold and 0.15 g inactivity threshold (both
    # relative to orientation at engagement) — picked up or tapped motion
    # easily exceeds 0.25 g, while a stationary board settles below 0.15 g.
    sensor.set_activity_threshold(0.25, referenced=True)                             # Set activity threshold, (threshold_g=0.25, referenced=True) → None
    sensor.set_inactivity_threshold(0.15, referenced=True)                            # Set inactivity threshold, (threshold_g=0.15, referenced=True) → None
    sensor.set_inactivity_time(30)                                                    # Set inactivity time, (samples=30) → None
                                                                                      # ~5 s at wake-up mode's ~6 Hz sample rate before INACT fires

    # --- Engage linked/loop mode and enable both detectors ---
    # Both ACT_EN and INACT_EN must be 1 to engage loop mode.
    sensor.enable_activity_detection(True)                                            # Enable activity detection, (enabled=True) → None
    sensor.enable_inactivity_detection(True)                                          # Enable inactivity detection, (enabled=True) → None
    sensor.set_link_loop_mode(ADXL362Full.LINKLOOP_LOOP)                              # Set link/loop mode, (mode=LOOP=3) → None
                                                                                      # chip autonomously toggles ACT/INACT without host servicing

    # --- Map AWAKE to INT2 and enter wake-up mode ---
    sensor.set_interrupt(2, ADXL362Full.SOURCE_AWAKE, True)                           # Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=True) → None
    sensor.set_wakeup_mode(True)                                                      # Enter wake-up mode, (enabled=True) → None
                                                                                      # POWER_CTL.WAKEUP=1 — ~270 nA idle, ~6 Hz sampling, single-sample activity only

    # --- Poll AWAKE for 60 s and count asleep↔awake transitions ---
    print("Watching for motion. Pick up or tap the board to wake; "
          "let it settle to sleep. Ctrl+C to exit.")                                 # Print demo header, () → None
    last_awake = None
    transitions = 0
    start = time.monotonic()                                                          # Get monotonic time, () → float s
    try:
        while time.monotonic() - start < 60:                                          # Loop until 60 s elapsed, () → bool
            now_awake = sensor.awake()                                                # Read AWAKE bit, () → bool
            if last_awake is None or now_awake != last_awake:
                state = "AWAKE" if now_awake else "asleep"
                print("{:6.2f}s  {}".format(time.monotonic() - start, state))        # Print timestamped state, () → None
                transitions += 1
                last_awake = now_awake
            time.sleep(0.2)                                                           # Sleep 200 ms between polls, () → None
    except KeyboardInterrupt:
        pass

    print("Total transitions observed: {}".format(transitions))                       # Print final count, () → None
    print("Note: during 'asleep' periods the ADXL362 draws ~270 nA — "
          "roughly two orders of magnitude below the ~1.8 µA of the "
          "continuous 100 Hz measurement mode used by the Minimal "
          "read() example.")                                                          # Print note, () → None


if __name__ == "__main__":
    main()