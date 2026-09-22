#!/usr/bin/env python3
"""Conformance checker for ADE7953 (power category). See
specs/testing_framework.md, "Conformance Implementation", and
specs/power/ade7953_timing.conf for the timing bounds checked here.

Keys off sigrok/power/ade7953/pd.py's named annotations (added alongside
the decoder's generic reg-write/data-read annotations).

Usage (invoked by each language's platform script's run_conformance(),
not directly).
"""
import argparse
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / 'conformance'))
import _sigrok_conformance as sc  # noqa: E402

TIMING_CONF = REPO_ROOT / 'specs' / 'power' / 'ade7953_timing.conf'
DECODER_ID = 'ade7953'

CHECKS = {
    # The Reset bit (20) in IRQSTATA is always enabled (cannot be
    # disabled) and is asserted at the end of every power-up. We check
    # that it is observed within the capture window after the host
    # initiates the Required Power-Up Register Setting.
    'powerup_after_reset': (
        lambda t: 'Write INTERNAL_RES: 0x30' in t,
        lambda t: 'Read IRQSTATA' in t,
    ),
}


def build_trigger(args):
    if args.binary:
        return lambda name: subprocess.run([args.binary], check=False)
    if args.port:
        return lambda name: sc._toggle_reset(args.port)
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
        test_file = REPO_ROOT / 'python' / 'tests' / 'power' / 'ade7953_test_linux.py'
        return lambda name: subprocess.run(
            [sys.executable, str(test_file)], check=False, cwd=str(REPO_ROOT / 'python'))
    if args.lang == 'nodejs':
        test_file = REPO_ROOT / 'nodejs' / 'tests' / 'power' / 'ade7953_test.js'
        return lambda name: subprocess.run(['node', str(test_file)], check=False)
    if args.lang == 'rust':
        return lambda name: subprocess.run(
            ['cargo', 'run', '--quiet', '-p', 'ade7953_test', '--release'],
            check=False, cwd=str(REPO_ROOT / 'rust'))
    if args.lang == 'go':
        test_file = REPO_ROOT / 'go' / 'tests' / 'power' / 'ade7953_test' / 'main.go'
        return lambda name: subprocess.run(['go', 'run', str(test_file)], check=False, cwd=str(REPO_ROOT / 'go'))
    if args.lang in ('jvm_java', 'jvm_kotlin', 'jvm_groovy'):
        ext = {'jvm_java': 'java', 'jvm_kotlin': 'kt', 'jvm_groovy': 'groovy'}[args.lang]
        test_file = REPO_ROOT / 'jvm' / 'tests' / 'power' / 'ade7953' / f'Ade7953Test.{ext}'
        return lambda name: subprocess.run(['jbang', str(test_file)], check=False)
    if args.lang in ('cpp_linux', 'cpp_arduino', 'cpp_zephyr', 'cpp_espidf', 'cpp_picosdk'):
        if args.lang == 'cpp_linux':
            test_bin = REPO_ROOT / 'cpp' / 'tests' / 'power' / 'ade7953_test_linux' / 'ade7953_test_linux'
            return lambda name: subprocess.run([str(test_bin)], check=False)
        # flashable platforms: build via the relevant platform script, but
        # the caller has already done that. Pass a binary if provided.
        return lambda name: subprocess.run(['true'], check=False)

    raise SystemExit(f'unknown --lang {args.lang!r}')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--lang', required=True)
    ap.add_argument('--binary')
    ap.add_argument('--port')
    ap.add_argument('--mp-test')
    ap.add_argument('--mp-port')
    ap.add_argument('--mp-mount')
    ap.add_argument('--cp-test')
    ap.add_argument('--cp-port')
    ap.add_argument('--jbang-test')
    args = ap.parse_args()
    trigger = build_trigger(args)
    sc.run_checks(DECODER_ID, TIMING_CONF, CHECKS, trigger)


if __name__ == '__main__':
    main()