#!/usr/bin/env python3
"""Conformance checker for NEO-6 (gnss category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/gnss/neo6_timing.conf for the timing bound checked here.

Check (see specs/gnss/neo-6.md, Timing Constraints):
  - poweron_ready: from power-on to the first NMEA/UBX sentence decoded
    off the UART line, >= poweron_ready_min_ms. No dedicated power-sense
    channel exists in this repo's testconfig_wiring (only the UART RX
    line is captured), so this is measured from capture start to the
    first decoded transaction - the operator is expected to power the
    module right as the capture begins, the same convention AHT21's
    poweron_ready check uses (see find_delta_samples()'s is_start=None
    case in _sigrok_conformance.py).

Unlike HX711/DHT11 (whose decoders consume raw logic channels directly),
sigrok/neo6/pd.py stacks on the standard libsigrokdecode `uart` decoder
(`inputs = ['uart']`) - the same way WS2812B's chip decoder stacks on
this repo's own `neopixel` transport decoder - so protocol='uart' and
decoder_id='neo6' here, with extra_decoder_opts passing the uart
decoder's own baudrate option (9600, NEO-6's factory default - see
specs/gnss/neo-6.md's UART section). This checker only exercises the
UART transport; I2C/SPI conformance would need a different protocol
stack and isn't covered here (matches every other chip's testing_framework
convention of testing whichever transport the wiring/sigrok config
actually captures).

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
gnss/neo6 - see specs/testing_framework.md, "Chip Wiring & Sigrok
Configuration"). SIGROK_CHANNELS here needs one channel matching the
uart decoder's `rx` role, e.g. "D0=RX".
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

TIMING_CONF = REPO_ROOT / 'specs' / 'gnss' / 'neo6_timing.conf'
DECODER_ID = 'neo6'
BAUDRATE = 9600

CHECKS = {
    'poweron_ready': (
        None,  # start = capture start, see module docstring
        lambda t: True,  # first decoded sentence/frame of any kind
    ),
}


def _reset_via_serial(port):
    """Toggle RTS/DTR to reset an already-flashed embedded board so its
    firmware's test sequence runs again inside the capture window - the
    same auto-reset trick cpp/read_serial_zephyr.py already uses. Not used
    for poweron_ready itself (see trigger() below), only for --lang
    fallback triggers this checker doesn't otherwise know how to run."""
    import serial
    s = serial.Serial(port, 115200, timeout=0.1)
    s.dtr = False
    s.rts = True
    time.sleep(0.1)
    s.rts = False
    time.sleep(0.05)
    s.close()


def build_trigger(args):
    """Return trigger(check_name) -> None. poweron_ready needs the
    operator to physically power-cycle the module (nothing else in this
    repo's tooling can do that), so it gets its own branch regardless of
    --lang, the same as AHT21's poweron_ready check."""
    def rerun():
        if args.binary:
            subprocess.run([args.binary], check=False)
        elif args.port:
            _reset_via_serial(args.port)
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
                             str(REPO_ROOT / 'python' / 'tests' / 'gnss' / 'neo6_test_linux_uart.py')],
                            check=False, cwd=str(REPO_ROOT / 'python'))
        elif args.lang == 'nodejs':
            subprocess.run(['node', str(REPO_ROOT / 'nodejs' / 'tests' / 'gnss' / 'neo6_test_uart.js')],
                            check=False)
        else:
            raise SystemExit(f"ERROR: don't know how to trigger a transaction for --lang {args.lang}")

    def trigger(name):
        if name == 'poweron_ready':
            print('=== Power-cycle the NEO-6 now (capture is running) ===', file=sys.stderr)
            time.sleep(args.serial_timeout if args.port else 1.0)
        else:
            rerun()

    return trigger


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
              "(testconfig_wiring's per-chip case block for gnss/neo6) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('NEO-6', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn,
                        protocol='uart', extra_decoder_opts=f':baudrate={BAUDRATE}')
    sys.exit(rc)


if __name__ == '__main__':
    main()
