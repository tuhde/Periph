#!/usr/bin/env python3
"""Conformance checker for RDA5807M (comms category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/comms/rda5807m_timing.conf for the timing bounds checked here.

Checks (see specs/comms/rda5807m.md, Timing Constraints):
  - ready_settle: from any register write to the next STATUSB read showing
    FM_READY set, <= ready_settle_max_ms (undocumented, measured on real
    hardware: FM_READY deasserts after any write and takes up to ~20 ms to
    reassert).
  - reset_recovery: from a write pulsing SOFT_RESET to the next STATUSA read
    showing STC set, >= reset_recovery_min_ms (undocumented, measured on
    real hardware: the chip needs >=200 ms after a SOFT_RESET pulse, or a
    standby re-enable write, before a subsequent TUNE will lock).

Both key off named annotation classes added to sigrok/rda5807m/pd.py
specifically for this conformance work (ready_settle_start/_done,
reset_recovery_start/_done) - this is new chip work, not a retrofit of a
decoder that predates the named-annotation convention (contrast
conformance/gas/ens160_conformance.py's docstring, which explains why ENS160
still uses generic text matching).

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
comms/rda5807m - see specs/testing_framework.md, "Chip Wiring and Sigrok
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

TIMING_CONF = REPO_ROOT / 'specs' / 'comms' / 'rda5807m_timing.conf'
DECODER_ID = 'rda5807m'

CHECKS = {
    'ready_settle': (
        lambda t: t == 'ready_settle_start',
        lambda t: t == 'ready_settle_done',
    ),
    'reset_recovery': (
        lambda t: t == 'reset_recovery_start',
        lambda t: t == 'reset_recovery_done',
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
    """Return trigger(check_name) -> None: drives one real RDA5807M HIL
    run (which exercises soft_reset()/standby() and hence both writes and
    reads needed for both checks) so it happens during the capture window.
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
        # RDA5807M has a dedicated Linux HIL test (unlike ENS160/AHT21's
        # shared i2c_auto-based file) - test_linux.sh's run_hil() prefers
        # _test_linux.py, falling back to _test.py.
        test_file = REPO_ROOT / 'python' / 'tests' / 'comms' / 'rda5807m_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'comms' / 'rda5807m_test.js'
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
              "(testconfig_wiring's per-chip case block for comms/rda5807m) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('RDA5807M', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
