"""PCF8574 hardware test — CircuitPython.

Requires _testconfig.py on the CIRCUITPY drive with:
    SDA, SCL, ADDR  (e.g. SDA='board.IO1', SCL='board.IO2', ADDR=0x20)
"""
import time
import _testconfig as cfg
import board
import busio
import digitalio
from periph.transport.i2c_circuitpython import I2CTransport
from periph.chips.io_expander.pcf8574 import Pcf8574Minimal, Pcf8574Full

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


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {} expected {}'.format(label, got, expected))
        failed += 1


i2c = busio.I2C(eval(cfg.SCL), eval(cfg.SDA))
transport = I2CTransport(i2c, cfg.ADDR)
chip = Pcf8574Minimal(transport)

# After init, shadow must be 0xFF
check_eq('init_shadow', chip._shadow, 0xFF)

# read_port returns a byte
port = chip.read_port()
check_true('read_port_range', 0 <= port <= 0xFF)

# CircuitPython _CPPin: direction and value properties
p0 = chip.pin(0)
check_true('pin_is_cppin', isinstance(p0, Pcf8574Minimal._CPPin))

p0.direction = digitalio.Direction.OUTPUT
check_eq('direction_output_shadow', chip._shadow & 0x01, 0)

p0.value = True
check_eq('value_true_shadow', chip._shadow & 0x01, 1)

p0.value = False
check_eq('value_false_shadow', chip._shadow & 0x01, 0)

p0.switch_to_input()
check_eq('switch_to_input_shadow', chip._shadow & 0x01, 1)

p0.switch_to_output(value=False)
check_eq('switch_to_output_false_shadow', chip._shadow & 0x01, 0)

p0.deinit()  # no-op; should not raise

# write_port and read_port
chip.write_port(mask=0xFF)
port2 = chip.read_port()
check_true('read_port_after_write', 0 <= port2 <= 0xFF)

# Full: clear_interrupt
full = Pcf8574Full(transport)
changed = full.clear_interrupt()
check_true('clear_interrupt_range', 0 <= changed <= 0xFF)

# Full: pull / drive_mode raise AttributeError
pf = full.pin(1)
try:
    _ = pf.pull
    check_true('pull_raises', False)
except AttributeError:
    check_true('pull_raises', True)

try:
    pf.pull = None
    check_true('pull_set_raises', False)
except AttributeError:
    check_true('pull_set_raises', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
