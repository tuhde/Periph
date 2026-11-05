#!/usr/bin/env python3
"""Conformance checker for ADS1113 (adc_dac category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/adc_dac/ads1113_timing.conf.

Checks conversion_done: from the Config write with OS=1 (conversion_start) to
the first CONVERSION read (conversion_done) the driver must wait >= 1/DR
(7.8125 ms at the default 128 SPS). Every language's hardware test performs
single-shot conversions, so re-running it puts a pair inside the capture
window.

Reads SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by
the calling script from the per-chip case block for adc_dac/ads1113).
"""
import argparse
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / 'conformance'))
import _sigrok_conformance as sc  # noqa: E402

TIMING_CONF = REPO_ROOT / 'specs' / 'adc_dac' / 'ads1113_timing.conf'
DECODER_ID = 'ads1113'

CHECKS = {
    'conversion_done': (
        lambda t: 'conversion_start' in t,
        lambda t: 'conversion_done' in t,
    ),
}


def build_trigger(args):
    """Return trigger(check_name) -> None. Re-runs the already-flashed/built
    HIL binary or process so its single-shot conversions happen inside the
    capture window - the same "rerun the HIL entry point" pattern every other
    conformance checker here uses."""
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
                             str(REPO_ROOT / 'python' / 'tests' / 'adc_dac' / 'ads1113_test_linux.py')],
                            check=False, cwd=str(REPO_ROOT / 'python'))
        elif args.lang == 'nodejs':
            subprocess.run(['node', str(REPO_ROOT / 'nodejs' / 'tests' / 'adc_dac' / 'ads1113_test.js')],
                            check=False)
        elif args.lang == 'go':
            subprocess.run(['go', 'run', str(REPO_ROOT / 'go' / 'tests' / 'adc_dac' / 'ads1113_test')],
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
              "(testconfig_wiring's per-chip case block for adc_dac/ads1113) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('ADS1113', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
