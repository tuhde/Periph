"""PCF8575 hardware test — MicroPython.

Requires _testconfig.py on the device with:
    I2C_ID, SDA, SCL, FREQ, ADDR
"""
import time
import _testconfig as cfg
from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
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


i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
chip = Pcf8575Minimal(transport)

# After init, shadow must be [0xFF, 0xFF] (all inputs)
check_eq('init_shadow_0', chip._shadow[0], 0xFF)
check_eq('init_shadow_1', chip._shadow[1], 0xFF)

# read_port returns a byte in range 0–255
port0 = chip.read_port(0)
port1 = chip.read_port(1)
check_true('read_port_0_range', 0 <= port0 <= 0xFF)
check_true('read_port_1_range', 0 <= port1 <= 0xFF)

# write_port and shadow update
chip.write_port(0, 0xAA)
check_eq('write_port_0_shadow', chip._shadow[0], 0xAA)
chip.write_port(1, 0x55)
check_eq('write_port_1_shadow', chip._shadow[1], 0x55)
chip.write_port(0, 0xFF)
chip.write_port(1, 0xFF)

# pin() proxy: drive P0 low and check shadow
p0 = chip.pin(0)
p0.init(Pcf8575Minimal.OUT)
p0.off()
check_eq('pin_off_shadow', chip._shadow[0] & 0x01, 0)

p0.on()
check_eq('pin_on_shadow', chip._shadow[0] & 0x01, 1)

p0.toggle()
check_eq('pin_toggle_shadow', chip._shadow[0] & 0x01, 0)
p0.on()

# value() write path updates shadow
p0.value(0)
check_eq('pin_value_write0_shadow', chip._shadow[0] & 0x01, 0)
p0.value(1)
check_eq('pin_value_write1_shadow', chip._shadow[0] & 0x01, 1)

# read_port reflects write (pin released high, should read 1 if floating)
chip.write_port(0, 0xFF)
chip.write_port(1, 0xFF)
port0_after = chip.read_port(0)
port1_after = chip.read_port(1)
check_true('read_after_all_input_0', 0 <= port0_after <= 0xFF)
check_true('read_after_all_input_1', 0 <= port1_after <= 0xFF)

# Full: clear_interrupt returns int
full = Pcf8575Full(transport)
changed = full.clear_interrupt()
check_true('clear_interrupt_range', 0 <= changed <= 0xFFFF)

print('===DONE: {} passed, {} failed==='.format(passed, failed))