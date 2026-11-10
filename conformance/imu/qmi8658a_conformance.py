#!/usr/bin/env python3
"""Conformance checker for QMI8658A (imu category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/imu/qmi8658a_timing.conf for the timing bounds checked here.

Checks (see specs/imu/qmi8658a.md, Timing Constraints):
  - reset_recovery: from the RESET write (register 0x60 <- 0xB0) to the next
    transaction, >= reset_recovery_min_ms (system turn-on time 15 ms; no
    register access is allowed meanwhile).
  - gyro_startup: from the CTRL7 write that sets gEN (bit 1) to the first
    read of a sensor-data register (start address 0x33-0x40), >=
    gyro_startup_min_ms (gyroscope turn-on time t1 = 150 ms, datasheet
    Table 8; the ODR-dependent 3/ODR filter settling is not checked).

Both key off the named annotation classes emitted by sigrok/qmi8658a/pd.py
(reset_recovery_start/_done, gyro_startup_start/_done); matching is by
substring so the tier-0 text may carry descriptive wording.

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
imu/qmi8658a - see specs/testing_framework.md, "Chip Wiring and Sigrok
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

TIMING_CONF = REPO_ROOT / 'specs' / 'imu' / 'qmi8658a_timing.conf'
DECODER_ID = 'qmi8658a'

CHECKS = {
    'reset_recovery': (
        lambda t: 'reset_recovery_start' in t,
        lambda t: 'reset_recovery_done' in t,
    ),
    'gyro_startup': (
        lambda t: 'gyro_startup_start' in t,
        lambda t: 'gyro_startup_done' in t,
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
    """Return trigger(check_name) -> None: drives one real QMI8658A HIL run
    (whose construction alone exercises the reset check; the test body then
    reads the sensor-data registers) so it happens during the capture window. Which
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

    if args.lang == 'python':
        # QMI8658A has a dedicated Linux HIL test - test_linux.sh's run_hil()
        # prefers _test_linux.py, falling back to _test.py.
        test_file = REPO_ROOT / 'python' / 'tests' / 'imu' / 'qmi8658a_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'imu' / 'qmi8658a_test.js'
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
              "(testconfig_wiring's per-chip case block for imu/qmi8658a) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('QMI8658A', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
