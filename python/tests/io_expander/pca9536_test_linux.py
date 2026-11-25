"""PCA9536 hardware test — Linux (smbus2 /dev/i2c-N).

Configuration via environment variables (set directly or via test_linux.sh + testconfig):
    LINUX_I2C_BUS  — I²C bus number (default 1)
    I2C_ADDR       — device address in hex (default 0x41)
Wiring for the loopback checks: jumper P0-P1 to P2-P3 (Pn <-> Pn+2).
"""
import os
import sys
import time

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', 1))
ADDR    = int(os.environ.get('I2C_ADDR', '0x41'), 16)

from periph.connection.i2c_linux import I2CConnection
from periph.chips.io_expander.pca9536 import Pca9536Minimal, Pca9536Full

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
chip = Pca9536Minimal(connection)

check_eq('init_config', chip._direction, 0xFF)
check_eq('init_shadow', chip._shadow, 0xFF)

port = chip.read_port()
check_true('read_port_range', 0 <= port <= 0x0F)

chip.write_port(0, 0x05)
check_eq('write_port_shadow', chip._shadow & 0x0F, 0x05)
chip.write_port(0, 0x0F)

full = Pca9536Full(connection)
check_eq('full_init_config', full._direction, 0xFF)

# --- Loopback: P0-P1 (outputs) wired to P2-P3 (inputs), Pn <-> Pn+2 ---
full.set_direction(0x0C)                        # P0-P1 outputs, P2-P3 inputs
check_eq('config_readback', full.read_register(0x03) & 0x0F, 0x0C)
full.write_port(0, 0x01)
check_eq('loopback_0x1', (full.read_port() >> 2) & 0x03, 0x01)
full.write_port(0, 0x02)
check_eq('loopback_0x2', (full.read_port() >> 2) & 0x03, 0x02)
full.write_port(0, 0x00)
check_eq('loopback_0x0', (full.read_port() >> 2) & 0x03, 0x00)

# --- Polarity inversion on the input pins ---
full.configure_polarity(0x0C)
check_eq('polarity_readback', full.read_register(0x02) & 0x0F, 0x0C)
check_eq('polarity_inverted', (full.read_port() >> 2) & 0x03, 0x03)
full.configure_polarity(0x00)

# --- Changed-pin mask ---
full.poll_changes()
full.write_port(0, 0x01)                        # P0 high -> P2 changes
check_eq('poll_changes_changed', full.poll_changes(), 0x04)
full.write_port(0, 0x00)

full.reset()
check_eq('reset_config', full.read_register(0x03) & 0x0F, 0x0F)
check_eq('reset_output', full.read_register(0x01) & 0x0F, 0x0F)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
