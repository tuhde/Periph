#!/usr/bin/env python3
"""Conformance checker for WS2812B (led category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/led/ws2812b_timing.conf for the timing bound checked here.

Check (see specs/led/ws2812b.md, Timing Constraints):
  - reset_pulse: the low period after a pixel transmission ends, >=50us.

WS2812B's chip decoder (sigrok/ws2812b/pd.py) stacks on this repo's own
`neopixel` transport decoder (sigrok/neopixel/pd.py), which already does
all the NZR bit-timing decoding (T0H/T1H/bit-period) needed just to turn
the waveform into bytes, and separately emits one "reset" annotation
whose own (start, end) span already *is* the measured reset-pulse
duration - there's no separate start/end event pair to look for here,
unlike every I2C/SPI chip's checks (see find_delta_samples()'s
is_end=None case in _sigrok_conformance.py). The neopixel decoder needs
its own `reset_us` option set to this chip's specific minimum (50 for
WS2812B, 80 for SK6812RGBW - see sk6812rgbw_conformance.py) so it knows
how long a low period must be before it counts as a reset rather than
still-incoming bit data.

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
led/ws2812b - see specs/testing_framework.md, "Chip Wiring & Sigrok
Configuration"). SIGROK_CHANNELS here only needs one channel, e.g.
"D0=DIN" (the neopixel decoder's single `din` channel).
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

TIMING_CONF = REPO_ROOT / 'specs' / 'led' / 'ws2812b_timing.conf'
DECODER_ID = 'ws2812b'
RESET_US = 50

CHECKS = {
    'reset_pulse': (
        lambda t: 'Reset' in t,
        None,  # measure this annotation's own width, not a start/end pair
    ),
}


def _reset_via_serial(port):
    """Toggle RTS/DTR to reset an already-flashed embedded board so its
    firmware's demo/fill sequence runs again inside the capture window -
    the same auto-reset trick cpp/read_serial_zephyr.py already uses."""
    import serial
    s = serial.Serial(port, 115200, timeout=0.1)
    s.dtr = False
    s.rts = True
    time.sleep(0.1)
    s.rts = False
    time.sleep(0.05)
    s.close()


def build_trigger(args):
    """Return trigger(check_name) -> None: sends one pixel transmission
    (any fill/off call ends with a reset-length idle tail) so it happens
    during the capture window. Which mechanism this is depends on how the
    calling script invoked us."""
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
        test_file = REPO_ROOT / 'python' / 'tests' / 'led' / 'ws2812b_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'led' / 'ws2812b_test.js'
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
              "(testconfig_wiring's per-chip case block for led/ws2812b) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('WS2812B', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn,
                        protocol='neopixel', extra_decoder_opts=f':reset_us={RESET_US}')
    sys.exit(rc)


if __name__ == '__main__':
    main()
