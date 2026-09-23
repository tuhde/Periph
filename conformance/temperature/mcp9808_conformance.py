#!/usr/bin/env python3
"""Conformance checker for MCP9808 (temperature category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/temperature/mcp9808_timing.conf for the timing bound checked here.

Checks (see specs/temperature/mcp9808.md, Timing Constraints):
  - register_access: one register read or write transaction addressed to
    the MCP9808, from its START through its STOP, <= register_access_max_us.
    This is the only host-observable, bus-checkable timing the chip has -
    temperature conversion runs continuously with no start/busy bit, and
    the bus time-out is a fault-recovery behavior, so it is the only check
    here.

sigrok/mcp9808/pd.py emits a dedicated `timing` annotation row for this
check (see specs/sigrok_annotations.md): tier 0 of each marker contains the
literal check name with a `_start`/`_done` suffix (`register_access_start` /
`register_access_done`), which the CHECKS predicates below match on directly.

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
temperature/mcp9808 - see specs/testing_framework.md, "Chip Wiring & Sigrok
Configuration"). Every language's HIL test performs many register reads and
writes, so re-running the whole HIL binary/test (like every other
conformance checker here) puts register accesses inside the capture window.
"""
import argparse
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / 'conformance'))
import _sigrok_conformance as sc  # noqa: E402

TIMING_CONF = REPO_ROOT / 'specs' / 'temperature' / 'mcp9808_timing.conf'
DECODER_ID = 'mcp9808'

CHECKS = {
    'register_access': (
        lambda t: 'register_access_start' in t,
        lambda t: 'register_access_done' in t,
    ),
}


def build_trigger(args):
    """Return trigger(check_name) -> None. Re-runs the already-flashed/built
    HIL binary or process so fresh register accesses (the identity check,
    temperature reads, and the resolution/boundary/CONFIG round-trips in
    every language's HIL test sequence) happen inside the capture window -
    the same "rerun the HIL entry point" pattern every other conformance
    checker here uses."""
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
                             str(REPO_ROOT / 'python' / 'tests' / 'temperature' / 'mcp9808_test_linux.py')],
                            check=False, cwd=str(REPO_ROOT / 'python'))
        elif args.lang == 'nodejs':
            subprocess.run(['node', str(REPO_ROOT / 'nodejs' / 'tests' / 'temperature' / 'mcp9808_test.js')],
                            check=False)
        elif args.lang == 'go':
            subprocess.run(['go', 'run', str(REPO_ROOT / 'go' / 'tests' / 'temperature' / 'mcp9808_test')],
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
              "(testconfig_wiring's per-chip case block for temperature/mcp9808) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('MCP9808', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
