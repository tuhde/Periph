"""PCF8575 hardware test — Linux kernel.

Run on host with:
    I2C_BUS=1 I2C_ADDR=0x20 python3 pcf8575_test_linux.py
"""
import os
import sys
import time

try:
    from smbus2 import SMBus
except ImportError:
    SMBus = None

from periph.transport.i2c_linux import I2CTransport
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


bus_num = int(os.environ.get('I2C_BUS', 1))
addr = int(os.environ.get('I2C_ADDR', '0x20'), 0)

transport = I2CTransport(bus_num, addr)
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
p0.init(Pcf8575Minimal.OUT)
p0.off()
check_eq('pin_off_shadow', chip._shadow[0] & 0x01, 0)

p0.on()
check_eq('pin_on_shadow', chip._shadow[0] & 0x01, 1)

p0.toggle()
check_eq('pin_toggle_shadow', chip._shadow[0] & 0x01, 0)
p0.on()

p0.value(0)
check_eq('pin_value_write0_shadow', chip._shadow[0] & 0x01, 0)
p0.value(1)
check_eq('pin_value_write1_shadow', chip._shadow[0] & 0x01, 1)

chip.write_port(0, 0xFF)
chip.write_port(1, 0xFF)
port0_after = chip.read_port(0)
port1_after = chip.read_port(1)
check_true('read_after_all_input_0', 0 <= port0_after <= 0xFF)
check_true('read_after_all_input_1', 0 <= port1_after <= 0xFF)

full = Pcf8575Full(transport)
changed = full.clear_interrupt()
check_true('clear_interrupt_range', 0 <= changed <= 0xFFFF)

print('===DONE: {} passed, {} failed==='.format(passed, failed))