"""MCP23017 hardware test — MicroPython.

Requires _testconfig.py on the device with:
    I2C_ID, SDA, SCL, FREQ, ADDR
"""
import time
import _testconfig as cfg
from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.io_expander.mcp23017 import Mcp23017Minimal, Mcp23017Full

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
        print('FAIL {}: got {:#x} expected {:#x}'.format(label, got, expected))
        failed += 1


i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
chip = Mcp23017Minimal(transport)

check_eq('init_iodira', chip._direction[0], 0x7F)
check_eq('init_iodirb', chip._direction[1], 0x7F)
check_eq('init_shadow_a', chip._shadow[0], 0x00)
check_eq('init_shadow_b', chip._shadow[1], 0x00)

port_a = chip.read_port(0)
check_true('read_port_a_range', 0 <= port_a <= 0xFF)
port_b = chip.read_port(1)
check_true('read_port_b_range', 0 <= port_b <= 0xFF)

chip.write_port(0, 0xAA)
check_eq('write_port_shadow_a', chip._shadow[0], 0xAA)

p0 = chip.pin(0, Mcp23017Minimal.IN)
p7 = chip.pin(7, Mcp23017Minimal.OUT)
p7.off()
check_eq('pin7_off_shadow', chip._shadow[0] & 0x80, 0x00)
p7.on()
check_eq('pin7_on_shadow', chip._shadow[0] & 0x80, 0x80)

chip.write_port(0, 0xFF)

p15 = chip.pin(15, Mcp23017Minimal.OUT)
p15.off()
check_eq('pin15_off_shadow', chip._shadow[1] & 0x80, 0x00)
p15.on()
check_eq('pin15_on_shadow', chip._shadow[1] & 0x80, 0x80)

full = Mcp23017Full(transport)
check_eq('full_init_iodira', full._direction[0], 0x7F)
check_eq('full_init_iodirb', full._direction[1], 0x7F)

full.configure_pullup(0, 0x3F)
check_eq('pullup_a', full._pullup[0], 0x3F)
full.configure_polarity(0, 0x00)

full.set_default_value(0, 0x00)
full.configure_interrupt(0, None, lambda m: None, mode='change')
full.stop_interrupt(0)

changed = full.clear_interrupt(0)
check_true('clear_interrupt_range', 0 <= changed <= 0xFF)

flags = full.read_interrupt_flags(0)
check_true('int_flags_range', 0 <= flags <= 0xFF)

print('===DONE: {} passed, {} failed==='.format(passed, failed))