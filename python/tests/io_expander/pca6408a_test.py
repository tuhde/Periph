"""PCA6408A hardware test — MicroPython.

Requires _testconfig.py on the device with:
    I2C_ID, SDA, SCL, FREQ, ADDR
Wiring for the loopback checks: jumper P0-P3 to P4-P7 (Pn <-> Pn+4).
"""
import time
import _testconfig as cfg
from machine import I2C, Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.io_expander.pca6408a import Pca6408aMinimal, Pca6408aFull

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
chip = Pca6408aMinimal(connection)

check_eq('init_config', chip._direction, 0xFF)
check_eq('init_shadow', chip._shadow, 0xFF)

port = chip.read_port()
check_true('read_port_range', 0 <= port <= 0xFF)

chip.write_port(0, 0x55)
check_eq('write_port_shadow', chip._shadow, 0x55)
chip.write_port(0, 0xFF)

full = Pca6408aFull(connection)
check_eq('full_init_config', full._direction, 0xFF)

# --- Loopback: P0-P3 (outputs) wired to P4-P7 (inputs), Pn <-> Pn+4 ---
full.set_direction(0xF0)                        # P0-P3 outputs, P4-P7 inputs
check_eq('config_readback', full.read_register(0x03), 0xF0)
full.write_port(0, 0x05)
check_eq('loopback_0x5', (full.read_port() >> 4) & 0x0F, 0x05)
full.write_port(0, 0x0A)
check_eq('loopback_0xA', (full.read_port() >> 4) & 0x0F, 0x0A)
full.write_port(0, 0x00)
check_eq('loopback_0x0', (full.read_port() >> 4) & 0x0F, 0x00)

# --- Polarity inversion on the input nibble ---
full.configure_polarity(0xF0)
check_eq('polarity_readback', full.read_register(0x02), 0xF0)
check_eq('polarity_inverted', (full.read_port() >> 4) & 0x0F, 0x0F)
full.configure_polarity(0x00)

# --- Changed-pin mask ---
full.poll_interrupt()
full.write_port(0, 0x01)                        # P0 high -> P4 changes
check_eq('poll_interrupt_changed', full.poll_interrupt(), 0x10)
full.write_port(0, 0x00)

full.reset()
check_eq('reset_config', full.read_register(0x03), 0xFF)
check_eq('reset_output', full.read_register(0x01), 0xFF)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
