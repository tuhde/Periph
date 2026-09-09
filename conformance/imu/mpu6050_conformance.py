#!/usr/bin/env python3
"""Conformance checker for MPU6050 (imu category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/imu/mpu6050_timing.conf for the timing bounds checked here.

Checks (see specs/imu/mpu6050.md, Timing Constraints):
  - reset_recovery: from the DEVICE_RESET write (PWR_MGMT_1 bit 7) to the
    next PWR_MGMT_1 write (the wake-up write, in this driver's init
    sequence), >= reset_recovery_min_ms ("After DEVICE_RESET: wait 100 ms
    before writing configuration").
  - gyro_startup: from the write that clears SLEEP (PWR_MGMT_1 bit 6) to
    the first sensor-data read at ACCEL_XOUT_H (0x3B), >=
    gyro_startup_min_ms ("Gyroscope startup from sleep: 35 ms", datasheet
    Table 8).

Both key off named annotation classes added to sigrok/mpu6050/pd.py
specifically for this conformance work (reset_recovery_start/_done,
gyro_startup_start/_done) - this is new chip work, not a retrofit of a
decoder that predates the named-annotation convention (contrast
conformance/gas/ens160_conformance.py's docstring, which explains why ENS160
still uses generic text matching).

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
imu/mpu6050 - see specs/testing_framework.md, "Chip Wiring and Sigrok
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

TIMING_CONF = REPO_ROOT / 'specs' / 'imu' / 'mpu6050_timing.conf'
DECODER_ID = 'mpu6050'

CHECKS = {
    'reset_recovery': (
        lambda t: t == 'reset_recovery_start',
        lambda t: t == 'reset_recovery_done',
    ),
    'gyro_startup': (
        lambda t: t == 'gyro_startup_start',
        lambda t: t == 'gyro_startup_done',
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
    """Return trigger(check_name) -> None: drives one real MPU6050 HIL run
    (whose construction alone exercises both checks - reset then wake, then
    the first sensor read) so it happens during the capture window. Which
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
        # MPU6050 has a dedicated Linux HIL test - test_linux.sh's run_hil()
        # prefers _test_linux.py, falling back to _test.py.
        test_file = REPO_ROOT / 'python' / 'tests' / 'imu' / 'mpu6050_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'imu' / 'mpu6050_test.js'
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
              "(testconfig_wiring's per-chip case block for imu/mpu6050) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('MPU6050', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
