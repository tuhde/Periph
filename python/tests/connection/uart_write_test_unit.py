"""Unit test for the MicroPython / CircuitPython UART write check (#767) — no hardware.

uart.write() returns the number of bytes accepted, or None on a TX timeout; a
short or None result must raise OSError, and RS-485 DE must be released either way.
"""

import sys
import time

# MicroPython's time.sleep_us does not exist on CPython; the connections only
# call it (MicroPython) or time.sleep (CircuitPython) for the RS-485 drain delay.
if not hasattr(time, 'sleep_us'):
    time.sleep_us = lambda us: None

from periph.connection.uart_micropython import UARTConnection as MpUART
from periph.connection.uart_circuitpython import UARTConnection as CpUART

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


class FakeUart:
    """machine.UART / busio.UART stand-in: write() returns `result(len)`."""

    def __init__(self, result):
        self.result = result
        self.sent = []

    def write(self, data):
        self.sent.append(bytes(data))
        return self.result(len(data))


class MpPin:
    """machine.Pin stand-in: value(level)."""

    def __init__(self):
        self.level = None

    def value(self, v):
        self.level = v


class CpPin:
    """digitalio.DigitalInOut stand-in: .value attribute."""

    value = None


def raises_oserror(fn):
    try:
        fn()
    except OSError:
        return True
    return False


for name, cls, make_pin, low in (('MicroPython', MpUART, MpPin, 0), ('CircuitPython', CpUART, CpPin, False)):
    conn = cls(FakeUart(lambda n: n), 9600)
    conn.write(b'\x01\x02\x03')
    check_true(name + ': full write succeeds', True)

    conn = cls(FakeUart(lambda n: n - 1), 9600)
    check_true(name + ': short write raises OSError', raises_oserror(lambda: conn.write(b'\x01\x02\x03')))

    conn = cls(FakeUart(lambda n: None), 9600)
    check_true(name + ': write() returning None (TX timeout) raises OSError',
               raises_oserror(lambda: conn.write(b'\x01')))

    pin = make_pin()
    conn = cls(FakeUart(lambda n: None), 9600, de_pin=pin)
    raises_oserror(lambda: conn.write(b'\x01'))
    level = pin.level if isinstance(pin, MpPin) else pin.value
    check_true(name + ': RS-485 DE released after a failed write', level == low)

    pin = make_pin()
    conn = cls(FakeUart(lambda n: n), 9600, de_pin=pin)
    conn.write(b'\x01\x02')
    level = pin.level if isinstance(pin, MpPin) else pin.value
    check_true(name + ': RS-485 DE released after a good write', level == low)

print('%d passed, %d failed' % (passed, failed))
sys.exit(1 if failed else 0)
