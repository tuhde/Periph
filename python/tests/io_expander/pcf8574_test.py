"""PCF8574 hardware test — MicroPython.

Requires _testconfig.py on the device with:
    I2C_ID, SDA, SCL, FREQ, ADDR
"""
import time
import _testconfig as cfg
from machine import I2C, Pin
from periph.transport.i2c_micropython import I2CTransport
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


i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
chip = Pcf8574Minimal(transport)

# After init, shadow must be 0xFF (all inputs)
check_eq('init_shadow', chip._shadow, 0xFF)

# read_port returns a byte in range 0–255
port = chip.read_port()
check_true('read_port_range', 0 <= port <= 0xFF)

# write_port and shadow update
chip.write_port(mask=0xAA)
check_eq('write_port_shadow', chip._shadow, 0xAA)
chip.write_port(mask=0xFF)

# pin() proxy: drive P0 low and check shadow
p0 = chip.pin(0)
p0.init(Pcf8574Minimal.OUT)
p0.off()
check_eq('pin_off_shadow', chip._shadow & 0x01, 0)

p0.on()
check_eq('pin_on_shadow', chip._shadow & 0x01, 1)

p0.toggle()
check_eq('pin_toggle_shadow', chip._shadow & 0x01, 0)
p0.on()

# value() write path updates shadow
p0.value(0)
check_eq('pin_value_write0_shadow', chip._shadow & 0x01, 0)
p0.value(1)
check_eq('pin_value_write1_shadow', chip._shadow & 0x01, 1)

# read_port reflects write (pin released high, should read 1 if floating)
chip.write_port(mask=0xFF)
port_after = chip.read_port()
check_true('read_after_all_input', 0 <= port_after <= 0xFF)

# Full: clear_interrupt returns int
full = Pcf8574Full(transport)
changed = full.clear_interrupt()
check_true('clear_interrupt_range', 0 <= changed <= 0xFF)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
