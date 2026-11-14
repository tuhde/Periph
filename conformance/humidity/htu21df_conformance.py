#!/usr/bin/env python3
"""Conformance checker for HTU21D-F (humidity category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/humidity/htu21df_timing.conf for the timing bound checked here.

Checks (see specs/humidity/htu21df.md, Timing Constraints):
  - conversion: from the STOP that ends a no-hold-master command (0xF3 / 0xF5) to the first
    read header the chip ACKs, <= conversion_max_ms (50 ms, 14-bit temperature,
    the slowest default measurement). A driver that polls the read header
    (clock stretching off) sees NACKs until the conversion is done; the
    check measures that window from the bus capture.

This is new work (not a retrofit of a pre-existing decoder): unlike
ENS160/AHT21, sigrok/htu21df/pd.py emits dedicated "conversion_start" /
"conversion_done" annotations specifically for this check (see
specs/_template_chip.md).

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
humidity/htu21df - see specs/testing_framework.md, "Chip Wiring and Sigrok
Configuration").
"""
import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / 'conformance'))
import _sigrok_conformance as sc  # noqa: E402

TIMING_CONF = REPO_ROOT / 'specs' / 'humidity' / 'htu21df_timing.conf'
DECODER_ID = 'htu21df'

CHECKS = {
    'conversion': (
        lambda t: 'conversion_start' in t,
        lambda t: 'conversion_done' in t,
    ),
}


def _reset_via_serial(port):
    """Toggle RTS/DTR to reset an already-flashed embedded board so its
    firmware's test sequence runs again inside the capture window - the
    same auto-reset trick cpp/read_serial_zephyr.py already uses for a
    fresh HIL run after flashing."""
    import serial
    s = serial.Serial(port, 115200, timeout=0.1)
    s.dtr = False
    s.rts = True
    time.sleep(0.1)
    s.rts = False
    time.sleep(0.05)
    s.close()


def build_trigger(args):
    """Return trigger(check_name) -> None: drives one real single-shot
    measurement (the HIL tests' own read flow) so it happens during the
    capture window. Which mechanism this is depends on
    how the calling script invoked us."""
    if args.binary:
        return lambda name: subprocess.run([args.binary], check=False)
    if args.port:
        return lambda name: _reset_via_serial(args.port)
    if args.mp_test:
        return lambda name: subprocess.run(
            ['mpremote', 'connect', args.mp_port, 'mount', args.mp_mount, 'run', args.mp_test],
            check=False)
    if args.cp_test:
        return lambda name: subprocess.run(
            [sys.executable, str(REPO_ROOT / 'python' / 'cp_runner.py'), args.cp_port, args.cp_test],
            check=False)
    if args.jbang_test:
        return lambda name: subprocess.run(['jbang', args.jbang_test], check=False)

    # Host languages with no compiled binary re-run their own HIL test file.
    if args.lang == 'python':
        test_file = REPO_ROOT / 'python' / 'tests' / 'humidity' / 'htu21df_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'humidity' / 'htu21df_test.js'
        return lambda name: subprocess.run(['node', str(test_file)], check=False)

    raise SystemExit(f"ERROR: don't know how to trigger a transaction for --lang {args.lang}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--lang', required=True)
    parser.add_argument('--binary')
    parser.add_argument('--port')
    parser.add_argument('--serial-timeout', type=float, default=20)
    parser.add_argument('--mp-port')
    parser.add_argument('--mp-mount')
    parser.add_argument('--mp-test')
    parser.add_argument('--cp-port')
    parser.add_argument('--cp-test')
    parser.add_argument('--jbang-test')
    args = parser.parse_args()

    sigrok_driver = os.environ.get('SIGROK_DRIVER')
    sigrok_channels = os.environ.get('SIGROK_CHANNELS')
    if not sigrok_driver or not sigrok_channels:
        print("ERROR: SIGROK_DRIVER and SIGROK_CHANNELS must be set "
              "(testconfig_wiring's per-chip case block for humidity/htu21df) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('HTU21D-F', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
