#!/usr/bin/env python3
"""Conformance checker for APDS-9930 (light category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/light/apds-9930_timing.conf for the timing bounds checked here.

Checks (see specs/light/apds-9930.md, Timing Constraints):

  - poweron_ready:          ≥ 4.5 ms between VDD-stable and first I²C START.
  - first_conversion_ready: ≥ 12 ms between ENABLE write with PON=1 and
                              the next STATUS read with both AVALID and
                              PVALID set.
  - als_integration_time:   ≈ 101 ms at the driver's default ATIME=0xDB
                              between AEN being set and AVALID becoming 1.
  - proximity_integration_time: ≈ 2.73 ms at the default PTIME=0xFF between
                              PEN being set and PVALID becoming 1.

Each check has its own start/end annotation pair in the sigrok decoder
(sigrok/apds9930/pd.py), named to match the keys in
specs/light/apds-9930_timing.conf verbatim.

Usage (invoked by each language's platform script's run_conformance(), not
directly). Reads SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the
environment (set by the calling script from testconfig_wiring's per-chip
case block for light/apds-9930 — see specs/testing_framework.md, "Chip
Wiring & Sigrok Configuration").
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

TIMING_CONF = REPO_ROOT / 'specs' / 'light' / 'apds-9930_timing.conf'
DECODER_ID = 'apds9930'

CHECKS = {
    'poweron_ready': (
        lambda t: 'poweron-start' in t,
        lambda t: 'poweron-done' in t,
    ),
    'first_conversion_ready': (
        lambda t: 'conversion-start' in t,
        lambda t: 'conversion-done' in t,
    ),
    'als_integration_time': (
        lambda t: 'als-integration-start' in t,
        lambda t: 'als-integration-done' in t,
    ),
    'proximity_integration_time': (
        lambda t: 'proximity-integration-start' in t,
        lambda t: 'proximity-integration-done' in t,
    ),
}


def _reset_via_serial(port):
    """Toggle RTS/DTR to reset an already-flashed embedded board so its
    firmware's test sequence runs again inside the capture window."""
    import serial
    s = serial.Serial(port, 115200, timeout=0.1)
    s.dtr = False
    s.rts = True
    time.sleep(0.1)
    s.rts = False
    time.sleep(0.05)
    s.close()


def build_trigger(args):
    """Return trigger(check_name) -> None: drives one real APDS-9930
    construction so it happens during the capture window."""
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
        test_file = REPO_ROOT / 'python' / 'tests' / 'light' / 'apds_9930_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'light' / 'apds-9930_test.js'
        return lambda name: subprocess.run(['node', str(test_file)], check=False)
    if args.lang == 'go':
        test_file = REPO_ROOT / 'go' / 'tests' / 'light' / 'apds_9930_test' / 'main.go'
        return lambda name: subprocess.run(['go', 'run', str(test_file)], check=False,
                                           cwd=str(REPO_ROOT / 'go'))

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
              "(testconfig_wiring's per-chip case block for light/apds-9930) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('APDS-9930', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()