"""PCF8575 hardware test — CircuitPython.

Requires _testconfig.py on the device with:
    I2C_ID, SCL, SDA, FREQ, ADDR
"""
import time
import board
import busio
import digitalio
import _testconfig as cfg
from periph.transport.i2c_circuitpython import I2CTransport
from periph.chips.io_expander.pcf8575 import Pcf8575Minimal, Pcf8575Full

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


i2c = busio.I2C(board.SCL, board.SDA, frequency=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
chip = Pcf8575Minimal(transport)

check_eq('init_shadow_0', chip._shadow[0], 0xFF)
check_eq('init_shadow_1', chip._shadow[1], 0xFF)

port0 = chip.read_port(0)
port1 = chip.read_port(1)
check_true('read_port_0_range', 0 <= port0 <= 0xFF)
check_true('read_port_1_range', 0 <= port1 <= 0xFF)

chip.write_port(0, 0xAA)
check_eq('write_port_0_shadow', chip._shadow[0], 0xAA)
chip.write_port(1, 0x55)
check_eq('write_port_1_shadow', chip._shadow[1], 0x55)
chip.write_port(0, 0xFF)
chip.write_port(1, 0xFF)

p0 = chip.pin(0)
p0.switch_to_output(value=False)
check_eq('pin_off_shadow', chip._shadow[0] & 0x01, 0)

p0.value = True
check_eq('pin_on_shadow', chip._shadow[0] & 0x01, 1)

chip.write_port(0, 0xFF)
chip.write_port(1, 0xFF)
port0_after = chip.read_port(0)
port1_after = chip.read_port(1)
check_true('read_after_all_input_0', 0 <= port0_after <= 0xFF)
check_true('read_after_all_input_1', 0 <= port1_after <= 0xFF)

full = Pcf8575Full(transport)
changed = full.clear_interrupt()
check_true('clear_interrupt_range', 0 <= changed <= 0xFFFF)

try:
    p0.pull
    print('FAIL pull_raises_attr_error')
    failed += 1
except AttributeError:
    print('PASS pull_raises_attr_error')

try:
    p0.drive_mode
    print('FAIL drive_mode_raises_attr_error')
    failed += 1
except AttributeError:
    print('PASS drive_mode_raises_attr_error')

print('===DONE: {} passed, {} failed==='.format(passed, failed))