#!/usr/bin/env python3
"""
Execute a Python script on a CircuitPython board via raw REPL.

Does NOT upload any files — assumes the board's filesystem is already prepared.
Works with CircuitPython 10+ (does not use ampy).

Usage:
    python3 cp_runner.py PORT SCRIPT_FILE [TIMEOUT_SECS]
"""
import sys
import time
import serial


def _flush(s):
    time.sleep(0.1)
    s.read(s.in_waiting)


def enter_raw_repl(s):
    s.write(b'\x03\x03')
    time.sleep(0.3)
    _flush(s)
    s.write(b'\x01')
    deadline = time.time() + 5
    buf = b''
    while time.time() < deadline:
        buf += s.read(s.in_waiting or 1)
        if b'raw REPL' in buf and buf.rstrip().endswith(b'>'):
            return
    raise RuntimeError(f'Could not enter raw REPL: {buf!r}')


def exec_raw(s, code, timeout=30):
    if isinstance(code, str):
        code = code.encode()
    for i in range(0, len(code), 256):
        s.write(code[i:i + 256])
        time.sleep(0.01)
    s.write(b'\x04')

    ok = b''
    deadline = time.time() + 5
    while time.time() < deadline:
        ok += s.read(1)
        if ok.endswith(b'OK'):
            break
    else:
        raise RuntimeError(f'No OK after exec: {ok!r}')

    out = b''
    deadline = time.time() + timeout
    while time.time() < deadline:
        out += s.read(s.in_waiting or 1)
        if out.count(b'\x04') >= 2:
            break

    parts = out.split(b'\x04')
    stdout = parts[0].decode('utf-8', errors='replace')
    stderr = parts[1].decode('utf-8', errors='replace').strip() if len(parts) > 1 else ''
    return stdout, stderr


def main():
    if len(sys.argv) < 3:
        print('Usage: cp_runner.py PORT SCRIPT_FILE [TIMEOUT]')
        sys.exit(1)

    port        = sys.argv[1]
    script_file = sys.argv[2]
    timeout     = int(sys.argv[3]) if len(sys.argv) > 3 else 30

    with open(script_file, 'r') as f:
        content = f.read()

    with serial.Serial(port, 115200, timeout=2) as s:
        enter_raw_repl(s)
        stdout, stderr = exec_raw(s, content, timeout=timeout)

    print(stdout, end='')
    if stderr:
        print('STDERR:', stderr, file=sys.stderr)

    failed = sum(1 for line in stdout.splitlines() if line.startswith('FAIL '))
    done   = any('===DONE' in line for line in stdout.splitlines())

    if not done:
        print('ERROR: test did not complete (===DONE not seen)')
        sys.exit(2)
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
