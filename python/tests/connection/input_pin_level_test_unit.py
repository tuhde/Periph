"""Unit test for InputPin.is_high() (level read) on every in-tree implementation — no hardware."""

import os
import sys
import types

from periph.connection.input_pin import (
    CircuitPythonPin, InputPin, LinuxPollingPin, LinuxSysfsPin, MicroPythonPin)

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


# ---- abstract contract: no default body --------------------------------------------
class NoLevel(InputPin):
    def on_edge(self, handler, trigger=InputPin.FALLING):
        pass

    def off_edge(self, handler):
        pass


try:
    NoLevel()
    check_true('InputPin without is_high cannot be instantiated', False)
except TypeError:
    check_true('InputPin without is_high cannot be instantiated', True)

# ---- MicroPythonPin --------------------------------------------------------------------


class FakeMPPin:
    def __init__(self):
        self.level = 0
        self.irq_calls = 0

    def value(self):
        return self.level

    def irq(self, **kw):
        self.irq_calls += 1


mp_raw = FakeMPPin()
mp = MicroPythonPin(mp_raw)
mp_raw.level = 1
check_true('MicroPythonPin high', mp.is_high() is True)
mp_raw.level = 0
check_true('MicroPythonPin low', mp.is_high() is False)
check_true('MicroPythonPin level read does not touch the IRQ', mp_raw.irq_calls == 0)

# ---- CircuitPythonPin ------------------------------------------------------------------


class FakeCPPin:
    def __init__(self):
        self.value = False


cp_raw = FakeCPPin()
cp = CircuitPythonPin(cp_raw)
cp_raw.value = True
check_true('CircuitPythonPin high', cp.is_high() is True)
cp_raw.value = False
check_true('CircuitPythonPin low', cp.is_high() is False)

# ---- LinuxPollingPin -------------------------------------------------------------------
state = {'v': False}
lp = LinuxPollingPin(lambda: state['v'])
check_true('LinuxPollingPin low', lp.is_high() is False)
state['v'] = True
check_true('LinuxPollingPin high', lp.is_high() is True)

# ---- LinuxSysfsPin with a fake gpiod ----------------------------------------------------
REQUESTS = []
LEVEL = {'high': False}


class Direction:
    INPUT = 'input'


class Edge:
    RISING = 'rising'
    FALLING = 'falling'
    BOTH = 'both'


class Value:
    ACTIVE = 'active'
    INACTIVE = 'inactive'


class LineSettings:
    def __init__(self, direction=None, edge_detection=None):
        self.direction = direction
        self.edge_detection = edge_detection


class FakeRequest:
    def __init__(self, line_config):
        self.line_config = line_config
        self.released = False
        r, w = os.pipe()
        self._r, self._w = r, w
        self.fd = r

    def get_value(self, offset):
        assert not self.released, 'read from a released request'
        return Value.ACTIVE if LEVEL['high'] else Value.INACTIVE

    def read_edge_events(self):
        return []

    def release(self):
        self.released = True
        os.close(self._r)
        os.close(self._w)


class FakeChip:
    def __init__(self, path):
        self.path = path

    def request_lines(self, consumer, line_config):
        live = [r for r in REQUESTS if not r.released]
        assert not live, 'gpiod allows one request per line: a live request still exists'
        req = FakeRequest(line_config)
        REQUESTS.append(req)
        return req


gpiod = types.ModuleType('gpiod')
gpiod_line = types.ModuleType('gpiod.line')
gpiod_line.Direction, gpiod_line.Edge, gpiod_line.Value = Direction, Edge, Value
gpiod.LineSettings = LineSettings
gpiod.Chip = FakeChip
gpiod.line = gpiod_line
sys.modules['gpiod'] = gpiod
sys.modules['gpiod.line'] = gpiod_line

pin = LinuxSysfsPin('/dev/gpiochip0', 7)
LEVEL['high'] = True
check_true('LinuxSysfsPin high without an edge request', pin.is_high() is True)
LEVEL['high'] = False
check_true('LinuxSysfsPin low reuses the level request', pin.is_high() is False and len(REQUESTS) == 1)
check_true('level request has no edge detection', REQUESTS[0].line_config[7].edge_detection is None)

pin.on_edge(lambda: None, InputPin.FALLING)
check_true('on_edge hands the line over: level request released, one edge request',
           len(REQUESTS) == 2 and REQUESTS[0].released and not REQUESTS[1].released
           and REQUESTS[1].line_config[7].edge_detection == Edge.FALLING)
LEVEL['high'] = True
check_true('level read while edges are armed uses the edge request', pin.is_high() is True and len(REQUESTS) == 2)

pin.off_edge(pin._handlers[0])
check_true('off_edge releases the edge request', REQUESTS[1].released)
check_true('level read works again after off_edge', pin.is_high() is True and len(REQUESTS) == 3)
REQUESTS[-1].release()

print('%d passed, %d failed' % (passed, failed))
sys.exit(1 if failed else 0)
