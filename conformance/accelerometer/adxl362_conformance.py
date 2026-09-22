#!/usr/bin/env python3
"""Conformance checker for ADXL362 (accelerometer category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/accelerometer/adxl362_timing.conf.

CHECKS is intentionally minimal: of the ADXL362's documented timing
constraints (see specs/accelerometer/adxl362.md, Timing Constraints),
only the data-ready clear latency (<=80 us between a data-register read
and STATUS.DATA_READY clearing) maps to a sigrok-observable annotation
pair (data_read_start/data_read_done, per the spec's Sigrok Decoder
section). Everything else is either a plain SPI-level bus timing
parameter the underlying SPI decoder already verifies, or a
millisecond/second-scale power-on-and-settle window with no fixed
before/after annotation pair to bound.

This one check is deferred until a calibrated hardware capture is
available: measuring it requires a synchronized trigger that fires a
data-register read during capture, same constraint noted in the
AD7705/AD7706 conformance checkers.

This script still exists (rather than being omitted) so the
run_conformance()-style platform-script wiring is uniform across every
chip.

Usage (invoked by each language's platform script's run_conformance(),
not directly): see build_trigger() below for the --lang-specific flags.
"""
import argparse
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / 'conformance'))
import _sigrok_conformance as sc  # noqa: E402

TIMING_CONF = REPO_ROOT / 'specs' / 'accelerometer' / 'adxl362_timing.conf'
DECODER_ID = 'adxl362'

CHECKS = {}


def build_trigger(args):
    """Return trigger(check_name) -> None. Present for interface parity
    with other chips' conformance scripts; not invoked since CHECKS is empty.
    """
    if args.binary:
        return lambda name: subprocess.run([args.binary], check=False)
    if args.port:
        import serial
        s = serial.Serial(args.port, 115200, timeout=0.1)
        s.dtr = False
        s.rts = True
        time.sleep(0.1)
        s.rts = False
        s.close()
        return lambda name: None
    if args.mp_test:
        return lambda name: subprocess.run(
            ['mpremote', 'connect', args.mp_port, 'mount', args.mp_mount, 'run', args.mp_test],
            check=False)
    if args.cp_test:
        return lambda name: subprocess.run(
            [sys.executable, str(REPO_ROOT / 'python' / 'cp_runner.py'),
             args.cp_port, args.cp_test], check=False)
    if args.jbang_test:
        return lambda name: subprocess.run(['jbang', args.jbang_test], check=False)
    if args.lang == 'python':
        test_file = REPO_ROOT / 'python' / 'tests' / 'accelerometer' / 'adxl362_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'accelerometer' / 'adxl362_test.js'
        return lambda name: subprocess.run(['node', str(test_file)], check=False)
    if args.lang == 'cpp':
        return lambda name: subprocess.run(
            [str(REPO_ROOT / 'cpp' / 'test_linux.sh'), 'accelerometer/adxl362_test_linux'],
            check=False, cwd=str(REPO_ROOT / 'cpp'))
    if args.lang == 'go':
        return lambda name: subprocess.run(
            ['go', 'run', './main.go'], check=False,
            cwd=str(REPO_ROOT / 'go' / 'tests' / 'accelerometer' / 'adxl362_test'))
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
              "(testconfig_wiring's per-chip case block for accelerometer/adxl362) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('ADXL362', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
