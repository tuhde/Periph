#!/usr/bin/env python3
"""Conformance checker for TMC5160 (motor category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/motor/tmc5160_timing.conf for the timing bounds checked here.

The chip has two transports; the checker selects the pair of timing markers
by the TMC5160_BUS environment variable ("spi", the default, or "uart") that
the calling script sets from testconfig_wiring's per-chip case block for
motor/tmc5160 (see specs/motor/tmc5160.md, Timing Constraints):

  - spi, check "datagram": a 40-bit SPI datagram, from the CSN falling edge
    (`datagram_start`) to the CSN rising edge (`datagram_end`), must take at
    least datagram_min_us (40 SCK clocks at the 4 MHz maximum). Decoder
    stacked on the standard libsigrokdecode `spi` decoder; SIGROK_CHANNELS
    maps the SPI signals, e.g. "D0=CLK,D1=MOSI,D2=MISO,D3=CS".
  - uart, check "reply_delay": from the end of the last byte of a read
    request (`read_request_end`) to the start of the first byte of the chip's
    reply (`read_reply_start`), <= reply_delay_max_us (SENDDELAY, default
    8 bit times, about 69 us at 115200 baud). Decoder stacked on the `uart`
    decoder (baudrate=115200); SIGROK_CHANNELS needs one channel matching the
    uart decoder's `rx` role, e.g. "D0=RX".

sigrok/tmc5160/pd.py emits both marker pairs on its timing row
(specs/sigrok_annotations.md); the predicates below match those tokens in
tier 0. Every language's HIL test issues several register accesses, so
re-running the whole HIL test puts datagrams inside the capture window.

Usage (invoked by each language's platform script's run_conformance(), not
directly): see build_trigger() below for the --lang-specific flags. Reads
SIGROK_DRIVER/SIGROK_CONN/SIGROK_CHANNELS/TMC5160_BUS from the environment.
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

TIMING_CONF = REPO_ROOT / 'specs' / 'motor' / 'tmc5160_timing.conf'
DECODER_ID = 'tmc5160'

BAUDRATE = 115200

SPI_CHECKS = {
    'datagram': (
        lambda t: 'datagram_start' in t,
        lambda t: 'datagram_end' in t,
    ),
}
UART_CHECKS = {
    'reply_delay': (
        lambda t: 'read_request_end' in t,
        lambda t: 'read_reply_start' in t,
    ),
}


def build_trigger(args):
    """Return trigger(check_name) -> None: re-run the language's HIL test so
    datagrams (and replies) appear inside the capture window."""
    if args.binary:
        return lambda name: subprocess.run([args.binary], check=False)
    if args.port:
        import serial
        s = serial.Serial(args.port, 115200, timeout=0.1)
        s.dtr = False
        s.rts = True
        time.sleep(0.1)
        s.rts = False
        s.close()
        return lambda name: None
    if args.mp_test:
        return lambda name: subprocess.run(
            ['mpremote', 'connect', args.mp_port, 'mount', args.mp_mount, 'run', args.mp_test],
            check=False)
    if args.cp_test:
        return lambda name: subprocess.run(
            [sys.executable, str(REPO_ROOT / 'python' / 'cp_runner.py'),
             args.cp_port, args.cp_test], check=False)
    if args.jbang_test:
        return lambda name: subprocess.run(['jbang', args.jbang_test], check=False)
    if args.lang == 'python':
        test_file = REPO_ROOT / 'python' / 'tests' / 'motor' / 'tmc5160_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'motor' / 'tmc5160_test.js'
        return lambda name: subprocess.run(['node', str(test_file)], check=False)
    if args.lang == 'cpp':
        return lambda name: subprocess.run(
            [str(REPO_ROOT / 'cpp' / 'test_linux.sh'), 'motor/tmc5160_test_linux'],
            check=False, cwd=str(REPO_ROOT / 'cpp'))
    if args.lang == 'go':
        return lambda name: subprocess.run(
            ['go', 'run', './main.go'], check=False,
            cwd=str(REPO_ROOT / 'go' / 'tests' / 'motor' / 'tmc5160_test'))
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
              "(testconfig_wiring's per-chip case block for motor/tmc5160) "
              "to run conformance checks.", file=sys.stderr)
        sys.exit(2)
    sigrok_conn = os.environ.get('SIGROK_CONN') or None

    bus = (os.environ.get('TMC5160_BUS') or 'spi').lower()
    if bus not in ('spi', 'uart'):
        print("ERROR: TMC5160_BUS must be 'spi' or 'uart'.", file=sys.stderr)
        sys.exit(2)

    trigger = build_trigger(args)
    if bus == 'uart':
        rc = sc.run_checks('TMC5160', DECODER_ID, sigrok_channels, UART_CHECKS, trigger,
                            str(TIMING_CONF), sigrok_driver, sigrok_conn,
                            protocol='uart', extra_decoder_opts=f':baudrate={BAUDRATE}')
    else:
        rc = sc.run_checks('TMC5160', DECODER_ID, sigrok_channels, SPI_CHECKS, trigger,
                            str(TIMING_CONF), sigrok_driver, sigrok_conn, protocol='spi')
    sys.exit(rc)


if __name__ == '__main__':
    main()
