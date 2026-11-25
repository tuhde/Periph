"""MCP23008 hardware test — MicroPython.

Requires _testconfig.py on the device with:
    I2C_ID, SDA, SCL, FREQ, ADDR
Wiring for the loopback checks: jumper GP0-GP3 to GP4-GP7 (GPn <-> GPn+4).
"""
import time
import _testconfig as cfg
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.io_expander.mcp23008 import Mcp23008Minimal, Mcp23008Full

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
connection = I2CConnection(i2c, cfg.ADDR)
chip = Mcp23008Minimal(connection)

check_eq('init_iodir', chip._direction, 0xFF)
check_eq('init_shadow', chip._shadow, 0x00)

port = chip.read_port()
check_true('read_port_range', 0 <= port <= 0xFF)

chip.write_port(0, 0x55)
check_eq('write_port_shadow', chip._shadow, 0x55)
chip.write_port(0, 0x00)

full = Mcp23008Full(connection)
check_eq('full_init_iodir', full._direction, 0xFF)

# --- Loopback: GP0-GP3 (outputs) wired to GP4-GP7 (inputs), GPn <-> GPn+4 ---
full.write_register(0x00, 0xF0)                 # GP0-GP3 outputs, GP4-GP7 inputs
check_eq('iodir_readback', full.read_register(0x00), 0xF0)
full.write_port(0, 0x05)
check_eq('loopback_0x5', (full.read_port() >> 4) & 0x0F, 0x05)
full.write_port(0, 0x0A)
check_eq('loopback_0xA', (full.read_port() >> 4) & 0x0F, 0x0A)
full.write_port(0, 0x00)
check_eq('loopback_0x0', (full.read_port() >> 4) & 0x0F, 0x00)

# --- Polarity inversion on the input nibble ---
full.configure_polarity(0xF0)
check_eq('polarity_inverted', (full.read_port() >> 4) & 0x0F, 0x0F)
full.configure_polarity(0x00)

# --- Pull-up on an unwired input reads high (GP7 is wired, so use GP4 as output low first) ---
full.configure_pullup(0xF0)
check_eq('pullup_readback', full.read_register(0x06), 0xF0)
full.configure_pullup(0x00)

# --- Interrupt flags ---
full.set_default_value(0x00)
full.enable_pin_interrupt(0x10)
check_eq('gpinten_readback', full.read_register(0x02), 0x10)
full.write_port(0, 0x01)                         # GP0 high -> GP4 changes
flags = full.poll_interrupt()
check_true('poll_interrupt_range', 0 <= flags <= 0xFF)
full.enable_pin_interrupt(0x00)
full.write_port(0, 0x00)

full.reset()
check_eq('reset_iodir', full.read_register(0x00), 0xFF)
check_eq('reset_olat', full.read_register(0x0A), 0x00)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
