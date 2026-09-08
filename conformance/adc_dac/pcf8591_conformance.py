#!/usr/bin/env python3
"""Conformance checker for PCF8591 (adc_dac category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/adc_dac/pcf8591_timing.conf.

CHECKS is intentionally empty: none of PCF8591's Timing Constraints (see
specs/adc_dac/pcf8591.md) have a bus-decodable start/end annotation pair to
check against with this repo's testconfig_wiring (SCL/SDA only, no AOUT
analog capture):
  - ADC conversion time / DAC settling time are analog settling behaviors
    with no second digital bus event marking their completion (unlike
    MCP4725/4728's RDY/BSY EEPROM-ready bit, PCF8591 has no status bit
    exposed over the bus at all).
  - I2C max SCL (100 kHz) is a clock-rate property, not an event-to-event
    delay this checker's model (first is_start(...) match -> first
    is_end(...) match after it) can express.
  - Auto-increment reads are explicitly self-paced by the bus per the
    datasheet - there is no minimum delay to verify.

This script still exists (rather than being omitted) so
run_conformance()-style platform-script wiring is uniform across every
chip: --lang/env-var validation behaves the same as every other chip's
checker, and sc.run_checks() against an empty CHECKS table is a harmless
no-op that prints "===DONE: 0 passed, 0 failed===" and exits 0.

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
adc_dac/pcf8591 - see specs/testing_framework.md, "Chip Wiring & Sigrok
Configuration").
"""
import argparse
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / 'conformance'))
import _sigrok_conformance as sc  # noqa: E402

TIMING_CONF = REPO_ROOT / 'specs' / 'adc_dac' / 'pcf8591_timing.conf'
DECODER_ID = 'pcf8591'

CHECKS = {}


def build_trigger(args):
    """Return trigger(check_name) -> None. Present for interface parity
    with every other chip's conformance script even though CHECKS is empty
    and this will never actually be called by run_checks()."""
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
        test_file = REPO_ROOT / 'python' / 'tests' / 'adc_dac' / 'pcf8591_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'adc_dac' / 'pcf8591_test.js'
        return lambda name: subprocess.run(['node', str(test_file)], check=False)

    raise SystemExit(f"ERROR: don't know how to trigger a transaction for --lang {args.lang}")


def _reset_via_serial(port):
    """Toggle RTS/DTR to reset an already-flashed embedded board so its
    firmware's test sequence runs again inside the capture window - the
    same auto-reset trick cpp/read_serial_zephyr.py already uses for a
    fresh HIL run after flashing."""
    import time
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
              "(testconfig_wiring's per-chip case block for adc_dac/pcf8591) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('PCF8591', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
