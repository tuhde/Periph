#!/usr/bin/env python3
"""Read serial output from an ESP32-S3 USB CDC port after flashing.

Usage: read_serial_esp32s3.py <port_hint> <baud> <timeout_secs>

Waits for the USB CDC device to (re-)enumerate after a watchdog reset, then
reads lines until ===DONE: ... === appears or the timeout expires.
Exits 0 on full pass, 1 on failures, 2 on timeout.
"""
import glob, os, sys, time
import serial

port_hint = sys.argv[1]
baud      = int(sys.argv[2])
timeout   = int(sys.argv[3])

def wait_for_port(hint, total_deadline):
    # First let any existing port disappear (watchdog reset disconnects USB)
    time.sleep(1.0)
    # Then wait for it to reappear (may be same or different ACM number)
    while time.time() < total_deadline:
        if os.path.exists(hint):
            return hint
        candidates = sorted(glob.glob('/dev/ttyACM*'))
        if candidates:
            return candidates[-1]
        time.sleep(0.2)
    return None

port = wait_for_port(port_hint, time.time() + 15)
if port is None:
    print('ERROR: no ACM serial port found within 15 s', file=sys.stderr)
    sys.exit(2)

# Small extra settle time after port appears
time.sleep(0.5)

for attempt in range(5):
    try:
        s = serial.Serial(port, baud, timeout=1, dsrdtr=False, rtscts=False)
        s.setDTR(False)
        s.setRTS(False)
        break
    except serial.SerialException:
        time.sleep(0.5)
else:
    print(f'ERROR: could not open {port}', file=sys.stderr)
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
