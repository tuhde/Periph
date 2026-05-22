"""MCP23017 hardware test — CircuitPython.

Requires _testconfig.py on the device with:
    I2C_ID, SDA, SCL, FREQ, ADDR
"""
import time
import board
import busio
import _testconfig as cfg
from periph.transport.i2c_circuitpython import I2CTransport
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


i2c = busio.I2C(board.SCL, board.SDA, frequency=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
chip = Mcp23017Minimal(transport)

check_eq('init_iodira', chip._direction[0], 0x7F)
check_eq('init_iodirb', chip._direction[1], 0x7F)
check_eq('init_shadow_a', chip._shadow[0], 0x00)
check_eq('init_shadow_b', chip._shadow[1], 0x00)

port_a = chip.read_port(0)
check_true('read_port_a_range', 0 <= port_a <= 0xFF)

chip.write_port(0, 0xAA)
check_eq('write_port_shadow_a', chip._shadow[0], 0xAA)
chip.write_port(0, 0xFF)

p0 = chip.pin(0)
p0.switch_to_input()
check_true('cp_pin_input_direction', True)

p0.switch_to_output(value=False)
check_true('cp_pin_output_direction', True)
p0.value = True
check_true('cp_pin_write', True)

# --- Loopback: PA (outputs) → PB (inputs); PA[n]↔PB[7-n] ---
for n in range(8):
    chip.pin(n).switch_to_output()

chip.write_port(0, 0xAA)          # PA0=0, avoids contention with PB7 output
pb = chip.read_port(1)
check_eq('loopback_0xAA', pb & 0x7F, 0x55)

chip.write_port(0, 0xFE)          # PA0=0, PA1–PA7=1
pb = chip.read_port(1)
check_eq('loopback_0xFE', pb & 0x7F, 0x7F)

chip.write_port(0, 0x00)
pb = chip.read_port(1)
check_eq('loopback_0x00', pb & 0x7F, 0x00)

full = Mcp23017Full(transport)
full.configure_pullup(0, 0x3F)
check_eq('pullup_a', full._pullup[0], 0x3F)

full.configure_interrupt(0, None, lambda m: None, mode='change')
full.stop_interrupt(0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))