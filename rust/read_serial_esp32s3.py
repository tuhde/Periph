#!/usr/bin/env python3
"""Read serial output from an ESP32-S3 USB CDC port after flashing.

Usage: read_serial_esp32s3.py <port> <baud> <timeout_secs>

Waits for the USB CDC device to (re-)enumerate after a flash reset, then
reads lines until ===DONE: ... === appears or the timeout expires.
Exits 0 on full pass, 1 on failures, 2 on timeout.
"""
import os, sys, time
import serial

port    = sys.argv[1]
baud    = int(sys.argv[2])
timeout = int(sys.argv[3])

# Wait for USB CDC to re-enumerate (espflash resets the board after flashing)
deadline_enum = time.time() + 15
while not os.path.exists(port):
    if time.time() > deadline_enum:
        print(f'ERROR: {port} did not appear within 15 s', file=sys.stderr)
        sys.exit(2)
    time.sleep(0.2)

time.sleep(0.5)  # let the CDC interface settle

try:
    s = serial.Serial(port, baud, timeout=1)
except serial.SerialException as e:
    print(f'ERROR: could not open {port}: {e}', file=sys.stderr)
    sys.exit(2)

deadline = time.time() + timeout
with s:
    while time.time() < deadline:
        try:
            raw = s.readline()
        except serial.SerialException:
            time.sleep(0.1)
            continue
        line = raw.decode('utf-8', errors='replace').rstrip()
        if line:
            print(line, flush=True)
        if '===DONE:' in line:
            sys.exit(0 if '0 failed' in line else 1)

print('ERROR: timeout waiting for ===DONE===', file=sys.stderr)
sys.exit(2)
