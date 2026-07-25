#!/usr/bin/env python3
"""Open a serial port, wait for the board to enumerate as USB CDC after
a picotool-triggered reset, and read until ===DONE=== or timeout."""
import sys
import time
import serial


def main():
    if len(sys.argv) < 2:
        print("Usage: read_serial_picosdk.py <port> [timeout_s]", file=sys.stderr)
        sys.exit(2)
    port    = sys.argv[1]
    timeout = float(sys.argv[2]) if len(sys.argv) > 2 else 20.0

    # picotool's reset causes the USB CDC device to re-enumerate. The
    # port briefly disappears; retry the open for up to `timeout` s.
    deadline_open = time.time() + timeout
    s = None
    while time.time() < deadline_open:
        try:
            s = serial.Serial(port, 115200, timeout=0.5)
            break
        except (serial.SerialException, FileNotFoundError):
            time.sleep(0.2)
    if s is None:
        print(f"Error: could not open {port} within {timeout:.0f}s", file=sys.stderr)
        sys.exit(2)

    passed = failed = None
    try:
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
            if text.startswith("PASS "):
                passed = (passed or 0) + 1
            elif text.startswith("FAIL "):
                failed = (failed or 0) + 1
            elif "===DONE" in text:
                import re
                m = re.search(r"(\d+) passed.*?(\d+) failed", text)
                if m:
                    passed = int(m.group(1))
                    failed = int(m.group(2))
                break
    finally:
        s.close()
    if failed is None:
        print("Error: test did not complete", file=sys.stderr)
        sys.exit(2)
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
