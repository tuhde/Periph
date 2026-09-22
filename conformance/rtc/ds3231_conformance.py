#!/usr/bin/env python3
"""Conformance checker for DS3231 (rtc category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/rtc/ds3231_timing.conf for the timing bound checked here.

Checks (see specs/rtc/ds3231.md, Timing Constraints):
  - temp_conversion_ready: from the CONTROL register's CONV bit (0x0E bit
    5) being written 1 until a CONTROL_STATUS read shows BSY (0x0F bit 2)
    clear, <= temp_conversion_ready_max_ms. This is the only
    host-triggerable, bus-observable timing constraint the chip has -
    everything else in the spec's Timing Constraints is either autonomous
    hardware behavior with no I2C signature, or generic I2C bus AC timing
    already covered by transport_i2c.md, so it is the only check here.

sigrok/rtc/ds3231/pd.py emits a dedicated `timing` annotation row for this
check (not the ordinary `data`/`status` text some older decoders key
timing checks off of - see specs/sigrok_annotations.md): tier 0 of each
marker contains the literal check name with a `_start`/`_done` suffix
(`temp_conversion_ready_start` / `temp_conversion_ready_done`), which the
CHECKS predicates below match on directly.

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
rtc/ds3231 - see specs/testing_framework.md, "Chip Wiring & Sigrok
Configuration"). Triggering this check means driving the HIL test's
force_temperature_conversion()-equivalent path so a CONV=1 write happens
inside the capture window; re-running the whole HIL binary/test (like
every other conformance checker here) is sufficient since that path is
exercised as part of the Full-class HIL test sequence.
"""
import argparse
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / 'conformance'))
import _sigrok_conformance as sc  # noqa: E402

TIMING_CONF = REPO_ROOT / 'specs' / 'rtc' / 'ds3231_timing.conf'
DECODER_ID = 'ds3231'

CHECKS = {
    'temp_conversion_ready': (
        lambda t: 'temp_conversion_ready_start' in t,
        lambda t: 'temp_conversion_ready_done' in t,
    ),
}


def build_trigger(args):
    """Return trigger(check_name) -> None. Re-runs the already-flashed/built
    HIL binary or process so a fresh force_temperature_conversion() call
    (part of every language's Full-class HIL test sequence) happens inside
    the capture window - the same "rerun the HIL entry point" pattern every
    other conformance checker here uses (see e.g.
    conformance/environmental/aht21_conformance.py)."""
    def rerun():
        if args.binary:
            subprocess.run([args.binary], check=False)
        elif args.port:
            import serial
            s = serial.Serial(args.port, 115200, timeout=0.1)
            s.dtr = False
            s.rts = True
            s.close()
        elif args.mp_test:
            subprocess.run(['mpremote', 'connect', args.mp_port, 'mount', args.mp_mount,
                             'run', args.mp_test], check=False)
        elif args.cp_test:
            subprocess.run([sys.executable, str(REPO_ROOT / 'python' / 'cp_runner.py'),
                             args.cp_port, args.cp_test], check=False)
        elif args.jbang_test:
            subprocess.run(['jbang', args.jbang_test], check=False)
        elif args.lang == 'python':
            subprocess.run([sys.executable,
                             str(REPO_ROOT / 'python' / 'tests' / 'rtc' / 'ds3231_test_linux.py')],
                            check=False, cwd=str(REPO_ROOT / 'python'))
        elif args.lang == 'nodejs':
            subprocess.run(['node', str(REPO_ROOT / 'nodejs' / 'tests' / 'rtc' / 'ds3231_test.js')],
                            check=False)
        elif args.lang == 'go':
            subprocess.run(['go', 'run', str(REPO_ROOT / 'go' / 'tests' / 'rtc' / 'ds3231_test')],
                            check=False, cwd=str(REPO_ROOT / 'go'))
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
              "(testconfig_wiring's per-chip case block for rtc/ds3231) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('DS3231', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
