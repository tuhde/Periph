#!/usr/bin/env python3
"""Conformance checker for ENS160 (gas category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/gas/ens160_timing.conf for the timing bounds checked here.

Checks (see specs/gas/ens160.md, Timing Constraints):
  - warmup_time: DEVICE_STATUS validity transitions from "Warm-up" to "OK"
    within warmup_time_min_s of power-on/idle-exit.
  - measurement_cycle: successive DATA_AQI reads in STANDARD mode land no
    more than measurement_cycle_max_ms apart.

Both key off sigrok/gas/ens160/pd.py's existing generic reg-read annotation
text (e.g. "DEVICE_STATUS: 0x02 (Validity: OK, NEWDAT)", "DATA_AQI: 5
bytes") rather than requiring new named start/end annotation classes -
unlike a brand-new chip (see specs/_template_chip.md), ENS160's decoder
predates this convention and its existing text already carries everything
these two checks need without risking behavior change to the decoder's
already-relied-on manual PulseView annotations.

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
gas/ens160 - see specs/testing_framework.md, "Chip Wiring & Sigrok
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

TIMING_CONF = REPO_ROOT / 'specs' / 'gas' / 'ens160_timing.conf'
DECODER_ID = 'ens160'

CHECKS = {
    'warmup_time': (
        lambda t: 'Validity: Warm-up' in t,
        lambda t: 'Validity: OK' in t,
    ),
    'measurement_cycle': (
        lambda t: 'DATA_AQI:' in t,
        lambda t: 'DATA_AQI:' in t,
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
    """Return trigger(check_name) -> None: drives one real ENS160
    transaction sequence so it happens during the capture window. Which
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

    # Host languages with no compiled binary re-run their own HIL test file.
    if args.lang == 'python':
        # ENS160 shares one i2c_auto-based test with test_mp.sh (auto-detects
        # Linux vs MicroPython) rather than a dedicated _test_linux.py.
        test_file = REPO_ROOT / 'python' / 'tests' / 'gas' / 'ens160_test.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'gas' / 'ens160_test.js'
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
              "(testconfig_wiring's per-chip case block for gas/ens160) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('ENS160', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
