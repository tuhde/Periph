#!/usr/bin/env python3
"""Conformance checker for MMA8451Q (accelerometer category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/accelerometer/mma8451q_timing.conf for the timing bounds checked here.

Checks (see specs/accelerometer/mma8451q.md, Timing Constraints):
  - first_data_ready: from the CTRL_REG1 (0x2A) write that sets ACTIVE = 1
    (the `active_start` timing marker) to the first STATUS (0x00) read that
    reports ZYXDR = 1 (the `active_ready` timing marker), <=
    first_data_ready_max_ms.

    The datasheet's turn-on time is 2/ODR + 1 ms (21 ms at the default
    100 Hz ODR); the bound allows 30 ms to cover bus and decode overhead.

Both markers live on sigrok/mma8451q/pd.py's `timing` row and carry the
check name `first_data_ready` in their tier-0 text, per
specs/sigrok_annotations.md's Variable-Length Annotation Convention.

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
accelerometer/mma8451q - see specs/testing_framework.md, "Chip Wiring &
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

TIMING_CONF = REPO_ROOT / 'specs' / 'accelerometer' / 'mma8451q_timing.conf'
DECODER_ID = 'mma8451q'

CHECKS = {
    'first_data_ready': (
        lambda t: 'active_start' in t,
        lambda t: 'active_ready' in t,
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
    """Return trigger(check_name) -> None: drives one real MMA8451Q HIL run
    (whose initialization sequence sets ACTIVE and then reads the first
    sample, so both markers of the pair land inside the capture window).
    Which mechanism this is depends on how the calling script invoked us."""
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

    if args.lang == 'python':
        # MMA8451Q has a dedicated Linux HIL test - test_linux.sh's run_hil()
        # prefers _test_linux.py, falling back to _test.py.
        test_file = REPO_ROOT / 'python' / 'tests' / 'accelerometer' / 'mma8451q_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'accelerometer' / 'mma8451q_test.js'
        return lambda name: subprocess.run(['node', str(test_file)], check=False)
    if args.lang == 'cpp':
        return lambda name: subprocess.run(
            [str(REPO_ROOT / 'cpp' / 'test_linux.sh'), 'accelerometer/mma8451q_test_linux'],
            check=False, cwd=str(REPO_ROOT / 'cpp'))
    if args.lang == 'go':
        return lambda name: subprocess.run(
            ['go', 'run', './main.go'], check=False,
            cwd=str(REPO_ROOT / 'go' / 'tests' / 'accelerometer' / 'mma8451q_test'))

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
              "(testconfig_wiring's per-chip case block for accelerometer/mma8451q) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('MMA8451Q', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
