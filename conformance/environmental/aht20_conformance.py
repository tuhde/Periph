#!/usr/bin/env python3
"""Conformance checker for AHT20 (environmental category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/environmental/aht20_timing.conf for the timing bounds checked here.

Checks (see specs/environmental/aht20.md, Timing Constraints):
  - measurement_done: from the "Trigger Measurement" (0xAC) command write
    (`measurement_start`) to the first read whose status has the busy bit
    clear (`measurement_done`), >= measurement_done_min_ms.

Both markers come from the `timing` row of sigrok/aht20/pd.py, per
specs/sigrok_annotations.md.

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
environmental/aht20 - see specs/testing_framework.md, "Chip Wiring &
Sigrok Configuration").
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

TIMING_CONF = REPO_ROOT / 'specs' / 'environmental' / 'aht20_timing.conf'
DECODER_ID = 'aht20'

CHECKS = {
    'measurement_done': (
        lambda t: 'measurement_start' in t,
        lambda t: 'measurement_done' in t,
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
    """Return trigger(check_name) -> None: re-run the HIL test so a fresh
    measurement happens inside the capture window."""
    def rerun():
        if args.binary:
            subprocess.run([args.binary], check=False)
        elif args.port:
            _reset_via_serial(args.port)
        elif args.mp_test:
            subprocess.run(['mpremote', 'connect', args.mp_port, 'mount', args.mp_mount,
                             'run', args.mp_test], check=False)
        elif args.cp_test:
            subprocess.run([sys.executable, str(REPO_ROOT / 'python' / 'cp_runner.py'),
                             args.cp_port, args.cp_test], check=False)
        elif args.jbang_test:
            subprocess.run(['jbang', args.jbang_test], check=False)
        elif args.lang == 'python':
            # AHT20 shares one i2c_auto-based test with test_mp.sh (auto-detects
            # Linux vs MicroPython) rather than a dedicated _test_linux.py.
            subprocess.run([sys.executable,
                             str(REPO_ROOT / 'python' / 'tests' / 'environmental' / 'aht20_test.py')],
                            check=False, cwd=str(REPO_ROOT / 'python'))
        elif args.lang == 'nodejs':
            subprocess.run(['node', str(REPO_ROOT / 'nodejs' / 'tests' / 'environmental' / 'aht20_test.js')],
                            check=False)
        else:
            raise SystemExit(f"ERROR: don't know how to trigger a transaction for --lang {args.lang}")

    def trigger(name):
        rerun()

    return trigger


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
              "(testconfig_wiring's per-chip case block for environmental/aht20) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('AHT20', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
