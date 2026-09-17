#!/usr/bin/env python3
"""Conformance checker for MCP2515 (comms category).

See specs/testing_framework.md, "Conformance Implementation", and
specs/comms/mcp2515_timing.conf for the timing bounds checked here.

Checks (see specs/comms/mcp2515.md, Timing Constraints):
  - reset_to_config: from the SPI RESET command (0xC0) to the next
    CS-asserted instruction, >= reset_to_config_min_us (>= 2 µs per
    datasheet; the driver delays 5 ms for margin).
  - mode_transition: from a CANCTRL write setting REQOP to the next
    CANSTAT read showing OPMOD matching, <= mode_transition_max_ms
    (< 1 ms typical; <= 2 ms bound for safety).

Both key off named annotation classes added to sigrok/mcp2515/pd.py
specifically for this conformance work.

Usage: invoked by each language's platform script's run_conformance(),
not directly. Reads SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the
environment.
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

TIMING_CONF = REPO_ROOT / 'specs' / 'comms' / 'mcp2515_timing.conf'
DECODER_ID = 'mcp2515'

CHECKS = {
    'reset_to_config': (
        lambda t: t == 'reset_to_config_start',
        lambda t: t == 'reset_to_config_done',
    ),
    'mode_transition': (
        lambda t: t == 'mode_transition_start',
        lambda t: t == 'mode_transition_done',
    ),
}


def build_trigger(args):
    """Return trigger(check_name) -> None: drives one real MCP2515 HIL
    run (which exercises init()/send()) so it happens during the capture
    window. Which mechanism this is depends on how the calling script
    invoked us."""
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
        test_file = REPO_ROOT / 'python' / 'tests' / 'comms' / 'mcp2515_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'comms' / 'mcp2515_test.js'
        return lambda name: subprocess.run(['node', str(test_file)], check=False)

    raise SystemExit(f"ERROR: don't know how to trigger a transaction for --lang {args.lang}")


def _reset_via_serial(port):
    """Toggle RTS/DTR to reset an already-flashed embedded board so its
    firmware's test sequence runs again inside the capture window."""
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
              "(testconfig_wiring's per-chip case block for comms/mcp2515) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('MCP2515', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn)
    sys.exit(rc)


if __name__ == '__main__':
    main()
