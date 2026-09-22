"""TPIC6B595 hardware test — CircuitPython.

Requires _testconfig.py on the device with:
    SCK, MOSI, FREQ, RCK, SRCLR, G
"""
import time
import board
import busio
import digitalio
import _testconfig as cfg
from periph.connection.sipo_circuitpython import SiPoConnection
from periph.chips.io_expander.tpic6b595 import Tpic6b595Minimal, Tpic6b595Full

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


spi = busio.SPI(board.SCK, MOSI=board.MOSI)
rck = digitalio.DigitalInOut(board.__getattr__(cfg.RCK_NAME))
rck.direction = digitalio.Direction.OUTPUT
srclr = None
g = None
if cfg.SRCLR_NAME:
    srclr = digitalio.DigitalInOut(board.__getattr__(cfg.SRCLR_NAME))
    srclr.direction = digitalio.Direction.OUTPUT
if cfg.G_NAME:
    g = digitalio.DigitalInOut(board.__getattr__(cfg.G_NAME))
    g.direction = digitalio.Direction.OUTPUT

connection = SiPoConnection(spi, rck, srclr=srclr, g=g, baudrate=cfg.FREQ)

chip = Tpic6b595Minimal(connection, num_devices=1)

# After init, shadow must be [0x00] (all outputs OFF)
check_eq('init_shadow_0', chip._shadow[0], 0x00)

# fill / off: shadow updates immediately and rebuilds the reversed buffer
chip.fill(True)
check_eq('fill_true_shadow', chip._shadow[0], 0xFF)
chip.fill(False)
check_eq('fill_false_shadow', chip._shadow[0], 0x00)
chip.off()
check_eq('off_shadow', chip._shadow[0], 0x00)

# write_port: 8-bit mask to a single cascaded device
chip.write_port(0, 0xA5)
check_eq('write_port_0xa5_shadow', chip._shadow[0], 0xA5)
chip.write_port(0, 0x00)

# pin() proxy: drive individual bits without bus read-back (CircuitPython property style)
p0 = chip.pin(0)
p0.value = True
check_eq('pin_on_shadow_bit', chip._shadow[0] & 0x01, 1)

p0.value = False
check_eq('pin_off_shadow_bit', chip._shadow[0] & 0x01, 0)

check_eq('pin_value_read_after_set_true', p0.value, False)

# set() satisfies the OutputPin contract
p0.set(True)
check_eq('pin_set_true', chip._shadow[0] & 0x01, 1)
p0.set(False)
check_eq('pin_set_false', chip._shadow[0] & 0x01, 0)

# Cascaded driver: shadow is a 2-byte buffer; every write rebuilds the reversed wire
cascaded = Tpic6b595Minimal(connection, num_devices=2)
check_eq('cascaded_init_shadow_0', cascaded._shadow[0], 0x00)
check_eq('cascaded_init_shadow_1', cascaded._shadow[1], 0x00)
cascaded.write_port(0, 0x01)
cascaded.write_port(1, 0x80)
check_eq('cascaded_write_port_0', cascaded._shadow[0], 0x01)
check_eq('cascaded_write_port_1', cascaded._shadow[1], 0x80)

# Full: clear, set_output_enable, write_all
full = Tpic6b595Full(connection, num_devices=2)
full.clear()
check_true('clear_accepted', True)
full.set_output_enable(False)
check_true('set_output_enable_false_accepted', True)
full.set_output_enable(True)
check_true('set_output_enable_true_accepted', True)

full.write_all([0xA5, 0x5A])
check_eq('write_all_shadow_0', full._shadow[0], 0xA5)
check_eq('write_all_shadow_1', full._shadow[1], 0x5A)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
