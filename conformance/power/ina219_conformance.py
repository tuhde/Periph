#!/usr/bin/env python3
"""Conformance checker for INA219 (power category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/power/ina219_timing.conf for the timing bounds checked here.

Checks (see specs/power/ina219.md, Timing Constraints):
  - wake_recovery: from a Configuration write that sets an active
    (non-zero) MODE to the next bus transaction, >= wake_recovery_min_us.
    Covers the chip's "Recovery from power-down mode: 40 us" constraint -
    any Configuration write leaving MODE != 0 counts (wake() and the
    Minimal driver's initial writes both qualify), not only an explicit
    wake() call.
  - conversion_cycle: successive Bus Voltage reads showing CNVR=1 land no
    more than conversion_cycle_max_ms apart. This is the chip's worst-case
    bound (128-sample averaging on both channels, run sequentially); with
    the driver's default 12-bit/532 us ADC settings the observed interval
    will be far under the bound - a real regression (e.g. the driver
    getting stuck or misreading CNVR) is what would actually trip it.

This is new work (not a retrofit of a pre-existing decoder): unlike
ENS160/AHT21, sigrok/ina219/pd.py emits dedicated "wake_write"/"wake_done"
and "conversion_ready" annotations specifically for these checks (see
specs/_template_chip.md).

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
power/ina219 - see specs/testing_framework.md, "Chip Wiring and Sigrok
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

TIMING_CONF = REPO_ROOT / 'specs' / 'power' / 'ina219_timing.conf'
DECODER_ID = 'ina219'

CHECKS = {
    'wake_recovery': (
        lambda t: 'wake_write' in t,
        lambda t: 'wake_done' in t,
    ),
    'conversion_cycle': (
        lambda t: 'conversion_ready' in t,
        lambda t: 'conversion_ready' in t,
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
    """Return trigger(check_name) -> None: drives one real INA219
    transaction sequence so it happens during the capture window. Which
    mechanism this is depends on how the calling script invoked us."""
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
        test_file = REPO_ROOT / 'python' / 'tests' / 'power' / 'ina219_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'power' / 'ina219_test.js'
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
              "(testconfig_wiring's per-chip case block for power/ina219) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('INA219', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
