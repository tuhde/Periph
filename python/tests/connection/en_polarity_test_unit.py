"""Unit test for Connection EN-pin polarity (en_active_high) — no hardware."""

import sys
from periph.connection.base import Connection
from periph.connection.output_pin import OutputPin

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


class RecordingPin(OutputPin):
    def __init__(self):
        self.levels = []

    def set(self, high):
        self.levels.append(high)


class NullConnection(Connection):
    def _read(self, n): return bytes(n)
    def _write(self, data): pass
    def _write_read(self, data, n): return bytes(n)


for active_high in (True, False):
    pin = RecordingPin()
    conn = NullConnection(en_pin=pin, en_active_high=active_high)
    conn.disable()
    conn.enable()
    check_true('disable drives %s for active_high=%s' % (not active_high, active_high),
               pin.levels[0] == (not active_high))
    check_true('enable drives %s for active_high=%s' % (active_high, active_high),
               pin.levels[1] == active_high)
    check_true('software gate unaffected by polarity (active_high=%s)' % active_high,
               conn.is_enabled())

pin = RecordingPin()
conn = NullConnection(en_pin=pin)
conn.enable()
check_true('default polarity is active-high', pin.levels == [True])

print('%d passed, %d failed' % (passed, failed))
sys.exit(1 if failed else 0)
