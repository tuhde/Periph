#!/usr/bin/env python3
"""Open a serial port, reset the board via RTS, read until ===DONE=== or timeout."""
import sys
import time
import serial

def main():
    if len(sys.argv) < 3:
        print("Usage: read_serial.py <port> <baud> [timeout_s]", file=sys.stderr)
        sys.exit(2)

    port    = sys.argv[1]
    baud    = int(sys.argv[2])
    timeout = float(sys.argv[3]) if len(sys.argv) > 3 else 20.0

    # Retry opening the port briefly in case west flash still holds it.
    deadline_open = time.time() + 5
    s = None
    while time.time() < deadline_open:
        try:
            s = serial.Serial(port, baud, timeout=0.5)
            break
        except serial.SerialException:
            time.sleep(0.2)
    if s is None:
        print(f"Error: could not open {port}", file=sys.stderr)
        sys.exit(2)

    # Reset into SPI-flash boot: DTR=0 keeps GPIO0 high, pulse RTS (EN pin).
    s.dtr = False
    s.rts = True;  time.sleep(0.1)
    s.rts = False; time.sleep(0.05)

    passed = failed = None
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            line = s.readline()
        except serial.SerialException:
            break
        if not line:
            continue
        text = line.decode("utf-8", errors="replace").rstrip()
        print(text, flush=True)
        if "===DONE" in text:
            import re
            m = re.search(r"(\d+) passed.*?(\d+) failed", text)
            if m:
                passed, failed = int(m.group(1)), int(m.group(2))
            break

    s.close()
    if failed is None:
        print("Error: test did not complete", file=sys.stderr)
        sys.exit(2)
    sys.exit(0 if failed == 0 else 1)

if __name__ == "__main__":
    main()
