#!/usr/bin/env python3
"""Read serial output from a test sketch until ===DONE=== or timeout."""
import sys
import time

def main():
    port    = sys.argv[1]
    timeout = int(sys.argv[2]) if len(sys.argv) > 2 else 15

    try:
        import serial
    except ImportError:
        print("ERROR: pyserial not installed. Run: pip install pyserial")
        sys.exit(2)

    time.sleep(2)  # wait for board reset after upload

    passed = failed = 0
    with serial.Serial(port, 115200, timeout=1) as s:
        deadline = time.time() + timeout
        while time.time() < deadline:
            line = s.readline().decode('utf-8', errors='replace').strip()
            if not line:
                continue
            print(line)
            if line.startswith('PASS'):
                passed += 1
            elif line.startswith('FAIL'):
                failed += 1
            elif line.startswith('===DONE'):
                break
        else:
            print('ERROR: timed out waiting for ===DONE===')
            sys.exit(2)

    sys.exit(0 if failed == 0 else 1)

if __name__ == '__main__':
    main()
