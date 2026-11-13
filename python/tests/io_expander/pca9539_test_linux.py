"""PCA9539 hardware test — Linux (smbus2 /dev/i2c-N).

Configuration via environment variables (set directly or via test_linux.sh + testconfig):
    LINUX_I2C_BUS  — I²C bus number (default 1)
    I2C_ADDR       — device address in hex (default 0x74)
Wiring for the loopback checks: jumper port 0 to port 1 (P0_n <-> P1_n).
"""
import os
import sys
import time

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', 1))
ADDR    = int(os.environ.get('I2C_ADDR', '0x74'), 16)

from periph.connection.i2c_linux import I2CConnection
from periph.chips.io_expander.pca9539 import Pca9539Minimal, Pca9539Full

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


connection = I2CConnection(I2C_BUS, ADDR)
chip = Pca9539Minimal(connection)

check_eq('init_config', chip._direction, 0xFFFF)
check_eq('init_shadow', chip._shadow, 0xFFFF)

check_true('read_port_0_range', 0 <= chip.read_port(0) <= 0xFF)
check_true('read_port_1_range', 0 <= chip.read_port(1) <= 0xFF)

chip.write_port(0, 0x55)
chip.write_port(1, 0xAA)
check_eq('write_port_shadow', chip._shadow, 0xAA55)
chip.write_port(0, 0xFF)
chip.write_port(1, 0xFF)

full = Pca9539Full(connection)
check_eq('full_init_config', full._direction, 0xFFFF)

# --- Loopback: port 0 (outputs) wired to port 1 (inputs), P0_n <-> P1_n ---
full.set_direction(0xFF00)                      # port 0 outputs, port 1 inputs
check_eq('config0_readback', full.read_register(0x06), 0x00)
check_eq('config1_readback', full.read_register(0x07), 0xFF)
full.write_port(0, 0x5A)
check_eq('loopback_0x5A', full.read_port(1), 0x5A)
full.write_port(0, 0xA5)
check_eq('loopback_0xA5', full.read_port(1), 0xA5)
full.write_all(0x0000)
check_eq('loopback_0x00', full.read_port(1), 0x00)
full.write_all(0x00FF)
check_eq('read_all_pair', full.read_all(), 0xFFFF)
full.write_all(0x0000)

# --- Polarity inversion on the input port ---
full.configure_polarity(0xFF00)
check_eq('polarity1_readback', full.read_register(0x05), 0xFF)
check_eq('polarity_inverted', full.read_port(1), 0xFF)
full.configure_polarity(0x0000)

# --- Changed-pin mask ---
full.poll_interrupt()
full.write_port(0, 0x01)                        # P00 high -> P10 changes
check_eq('poll_interrupt_changed', full.poll_interrupt(), 0x0100)
full.write_port(0, 0x00)

full.reset()
check_eq('reset_config0', full.read_register(0x06), 0xFF)
check_eq('reset_config1', full.read_register(0x07), 0xFF)
check_eq('reset_output0', full.read_register(0x02), 0xFF)
check_eq('reset_output1', full.read_register(0x03), 0xFF)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
