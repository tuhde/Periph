#!/usr/bin/env python3
"""Conformance checker for LSM303 (accelerometer category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/accelerometer/lsm303_timing.conf.

Checks (see specs/accelerometer/lsm303.md, Timing Constraints):
  - first_data_ready: from a CTRL_REG1_A (0x20) write with ODR != 0000
    (odr_start) to the first STATUS_REG_A (0x27) read with ZYXDA=1
    (odr_ready), <= 80 ms at the default 100 Hz high-resolution init
    (7/ODR = 70 ms turn-on, datasheet Table 8 + margin).

Keys off sigrok/lsm303/pd.py's `odr_start` / `odr_ready` timing-marker
pair on the `timing` annotation row (the conf check name is
first_data_ready; the decoder's markers carry it in tier 0 verbatim).

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
accelerometer/lsm303).
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

TIMING_CONF = REPO_ROOT / 'specs' / 'accelerometer' / 'lsm303_timing.conf'
DECODER_ID = 'lsm303'

CHECKS = {
    # first_data_ready: from a CTRL_REG1_A write with ODR != 0000
    # (odr_start) to the first STATUS_REG_A read with ZYXDA=1 (odr_ready).
    'first_data_ready': (
        lambda t: 'odr_start' in t,
        lambda t: 'odr_ready' in t,
    ),
}


def build_trigger(args):
    """Return trigger(check_name) -> result. Drives the language's HIL test
    (the init / set_data_rate sequence leaves power-down and then reads
    STATUS_REG_A, emitting the odr_start / odr_ready annotation pair)."""
    if args.binary:
        return lambda name: subprocess.run([args.binary], check=False)
    if args.port:
        return lambda name: _toggle_reset(args.port)
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
        test_file = REPO_ROOT / 'python' / 'tests' / 'accelerometer' / 'lsm303_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'accelerometer' / 'lsm303_test.js'
        return lambda name: subprocess.run(['node', str(test_file)], check=False)
    if args.lang == 'rust':
        # Cargo workspace test binary.
        return lambda name: subprocess.run(
            ['cargo', 'run', '--quiet', '-p', 'lsm303_test', '--release'],
            check=False, cwd=str(REPO_ROOT / 'rust'))
    if args.lang == 'go':
        return lambda name: subprocess.run(
            ['go', 'run', './tests/accelerometer/lsm303_test'],
            check=False, cwd=str(REPO_ROOT / 'go'))
    if args.lang == 'jvm':
        # Use the Linux HIL test (JBang).
        test_file = REPO_ROOT / 'jvm' / 'tests' / 'accelerometer' / 'lsm303' / 'LSM303Test.java'
        return lambda name: subprocess.run(['jbang', str(test_file)], check=False)
    if args.lang == 'cpp':
        # The C++ Linux test runs in /tmp/lsm303_test_linux once built.
        return lambda name: subprocess.run(['/tmp/lsm303_test_linux'], check=False)

    raise SystemExit(f"ERROR: don't know how to trigger a transaction for --lang {args.lang}")


def _toggle_reset(port):
    import serial
    s = serial.Serial(port, 115200, timeout=0.1)
    s.dtr = False
    s.rts = True
    time.sleep(0.1)
    s.rts = False
    time.sleep(0.05)
    s.close()


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
              "(testconfig_wiring's per-chip case block for accelerometer/lsm303) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('LSM303', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
