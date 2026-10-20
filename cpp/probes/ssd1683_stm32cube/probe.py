#!/usr/bin/env python3
"""Send commands to the SSD1683 probe over the ST-LINK VCP and print the replies.

Usage:  probe.py [--port /dev/ttyACM0] [--timeout 90] CMD [CMD ...]
Each CMD is one console line (quote multi-word commands), e.g.:
    probe.py status reset init "c 22 f7"
Waits for the "> " prompt after each command (the update command takes ~15 s).
"""
import argparse
import sys
import time

import serial


def read_until_prompt(ser, timeout):
    buf = b""
    deadline = time.time() + timeout
    while time.time() < deadline:
        chunk = ser.read(256)
        if chunk:
            buf += chunk
            if buf.endswith(b"> "):
                return buf.decode(errors="replace"), True
    return buf.decode(errors="replace"), False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default="/dev/ttyACM0")
    ap.add_argument("--timeout", type=float, default=90.0)
    ap.add_argument("commands", nargs="*")
    args = ap.parse_args()

    ser = serial.Serial(args.port, 115200, timeout=0.2)
    ser.reset_input_buffer()
    ser.write(b"\r")                    # wake the prompt (an empty line is ignored by the probe)
    out, ok = read_until_prompt(ser, 3.0)
    sys.stdout.write(out)
    for command in args.commands:
        for ch in command.encode() + b"\r":    # the probe echoes each char; don't outrun it
            ser.write(bytes([ch]))
            time.sleep(0.003)
        out, ok = read_until_prompt(ser, args.timeout)
        sys.stdout.write(out)
        if not ok:
            sys.stdout.write("\n[probe.py: no prompt within %.0f s]\n" % args.timeout)
            break
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
