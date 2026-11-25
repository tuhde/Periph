#!/usr/bin/env python3
"""Conformance checker for VL6180X (tof category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/tof/vl6180x_timing.conf.

The VL6180X's stated timing constraints (the range convergence time is a typical value
only - the sample is signalled by polling RESULT__INTERRUPT_STATUS_GPIO - the power-up
delay is a supply/CE event and the I2C limits are clock-rate properties) have no
bus-decodable start/end annotation pair, so the timing config defines no checks and
CHECKS below is empty. The checker is still provided so the platform scripts'
run_conformance() has a uniform entry point:
it runs zero checks and reports "===DONE: 0 passed, 0 failed===".

Reads SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by
the calling script from testconfig_wiring's per-chip case block for
tof/vl6180x).
"""
import argparse
import os
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / 'conformance'))
import _sigrok_conformance as sc  # noqa: E402

TIMING_CONF = REPO_ROOT / 'specs' / 'tof' / 'vl6180x_timing.conf'
DECODER_ID = 'vl6180x'

CHECKS = {}


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
    parser.parse_args()

    sigrok_driver = os.environ.get('SIGROK_DRIVER')
    sigrok_channels = os.environ.get('SIGROK_CHANNELS')
    if not sigrok_driver or not sigrok_channels:
        print("ERROR: SIGROK_DRIVER and SIGROK_CHANNELS must be set "
              "(testconfig_wiring's per-chip case block for tof/vl6180x) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    rc = sc.run_checks('VL6180X', DECODER_ID, sigrok_channels, CHECKS, lambda name: None,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
