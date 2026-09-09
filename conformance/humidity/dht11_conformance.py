#!/usr/bin/env python3
"""Conformance checker for DHT11 (humidity category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/humidity/dht11_timing.conf for the timing bound checked here.

Check (see specs/transport_dhtxx.md, Timing Constraints - T_be):
  - start_signal: the host start signal, DATA driven LOW for 18-30ms
    before being released, at the beginning of every DHTxx transaction.

DHT11's protocol has no shared I2C/SPI/NeoPixel-style bus to stack a chip
decoder on - sigrok/dhtxx/pd.py consumes the raw DATA logic channel
directly (`inputs = ['logic']`), the same way HX711's decoder does. So
this checker passes protocol='dhtxx' with decoder_id=None (see
decode_annotations()'s decoder_id=None case in _sigrok_conformance.py) -
there's nothing to stack on top of it. Unlike HX711 (chip-specific
decoder) this decoder is shared with the not-yet-implemented DHT22, hence
the 'dhtxx' name rather than 'dht11'.

The decoder only emits "Start: N ms" once the host start LOW pulse has
ended, so - like WS2812B's reset_pulse and HX711's powerdown_pulse checks
- the annotation's own (start, end) span already *is* the measured LOW
time; there's no separate start/end event pair to look for (see
find_delta_samples()'s is_end=None case).

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
humidity/dht11 - see specs/testing_framework.md, "Chip Wiring & Sigrok
Configuration"). SIGROK_CHANNELS here needs one channel matching the
decoder's channel id, e.g. "D0=DATA".
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

TIMING_CONF = REPO_ROOT / 'specs' / 'humidity' / 'dht11_timing.conf'

CHECKS = {
    'start_signal': (
        lambda t: t.startswith('Start:'),
        None,  # measure this annotation's own width, not a start/end pair
    ),
}


def _reset_via_serial(port):
    """Toggle RTS/DTR to reset an already-flashed embedded board so its
    firmware's test sequence (which calls read() and so issues a fresh
    start signal - see each language's dht11_test_linux.py / dht11_test.js
    / etc.) runs again inside the capture window - the same auto-reset
    trick cpp/read_serial_zephyr.py already uses."""
    import serial
    s = serial.Serial(port, 115200, timeout=0.1)
    s.dtr = False
    s.rts = True
    time.sleep(0.1)
    s.rts = False
    time.sleep(0.05)
    s.close()


def build_trigger(args):
    """Return trigger(check_name) -> None: runs the chip's existing HIL
    test (or, for Python/Node.js, its own connection-mocked test file -
    see below) so a start signal happens during the capture window. Which
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

    if args.lang == 'python':
        test_file = REPO_ROOT / 'python' / 'tests' / 'humidity' / 'dht11_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'humidity' / 'dht11_test.js'
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
              "(testconfig_wiring's per-chip case block for humidity/dht11) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('DHT11', None, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn,
                        protocol='dhtxx')
    sys.exit(rc)


if __name__ == '__main__':
    main()
