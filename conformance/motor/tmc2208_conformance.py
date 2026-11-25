#!/usr/bin/env python3
"""Conformance checker for TMC2208 (motor category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/motor/tmc2208_timing.conf for the timing bound checked here.

Check (see specs/motor/tmc2208.md, Timing Constraints):
  - reply_delay: from the end of the last byte of a read request to the
    start of the first byte of the chip's reply, <= reply_delay_max_us (the
    chip waits SENDDELAY, default 8 bit times = ~69 us at 115200 baud).
    sigrok/tmc2208/pd.py emits a `read_request_end` marker on the last
    request byte and a `read_reply_start` marker on the first reply byte
    (specs/sigrok_annotations.md timing row); the predicates below match
    those tokens in tier 0.

The decoder stacks on the standard libsigrokdecode `uart` decoder, so
protocol='uart' and decoder_id='tmc2208', with extra_decoder_opts passing
the uart decoder's baudrate option (115200, the library default). The
checker captures the single PDN_UART wire: SIGROK_CHANNELS needs one
channel matching the uart decoder's `rx` role, e.g. "D0=RX".

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS from the environment (set by the
calling script from testconfig_wiring/testconfig's per-chip case block for
motor/tmc2208 - see specs/testing_framework.md, "Chip Wiring & Sigrok
Configuration"). Every language's HIL test issues several register reads,
so re-running the whole HIL binary/test puts read requests inside the
capture window.
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

TIMING_CONF = REPO_ROOT / 'specs' / 'motor' / 'tmc2208_timing.conf'
DECODER_ID = 'tmc2208'
BAUDRATE = 115200

CHECKS = {
    'reply_delay': (
        lambda t: 'read_request_end' in t,
        lambda t: 'read_reply_start' in t,
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
    """Return trigger(check_name) -> None: re-run the language's HIL test so
    read requests (and their replies) appear inside the capture window."""
    def trigger(name):
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
                             str(REPO_ROOT / 'python' / 'tests' / 'motor' / 'tmc2208_test_linux.py')],
                            check=False, cwd=str(REPO_ROOT / 'python'))
        elif args.lang == 'nodejs':
            subprocess.run(['node', str(REPO_ROOT / 'nodejs' / 'tests' / 'motor' / 'tmc2208_test.js')],
                            check=False)
        else:
            raise SystemExit(f"ERROR: don't know how to trigger a transaction for --lang {args.lang}")

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
              "(testconfig_wiring's per-chip case block for motor/tmc2208) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    trigger = build_trigger(args)
    rc = sc.run_checks('TMC2208', DECODER_ID, sigrok_channels, CHECKS, trigger,
                        str(TIMING_CONF), sigrok_driver, sigrok_conn,
                        protocol='uart', extra_decoder_opts=f':baudrate={BAUDRATE}')
    sys.exit(rc)


if __name__ == '__main__':
    main()
