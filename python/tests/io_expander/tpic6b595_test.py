"""TPIC6B595 hardware test — MicroPython.

Requires _testconfig.py on the device with:
    SPI_ID, SDA/SCLK (SPI bus pins), FREQ, RCK, SRCLR, G
"""
import time
import _testconfig as cfg
from machine import SPI, Pin
from periph.connection.sipo_micropython import SiPoConnection
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


spi = SPI(cfg.SPI_ID, baudrate=cfg.FREQ, polarity=0, phase=0,
          sck=Pin(cfg.SCK), mosi=Pin(cfg.MOSI))
rck = Pin(cfg.RCK, Pin.OUT)
srclr = Pin(cfg.SRCLR, Pin.OUT) if cfg.SRCLR >= 0 else None
g = Pin(cfg.G, Pin.OUT) if cfg.G >= 0 else None
connection = SiPoConnection(spi, rck, srclr=srclr, g=g)

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

# pin() proxy: drive individual bits without bus read-back
p0 = chip.pin(0)
p0.on()
check_eq('pin_on_shadow_bit', chip._shadow[0] & 0x01, 1)

p0.off()
check_eq('pin_off_shadow_bit', chip._shadow[0] & 0x01, 0)

p0.toggle()
check_eq('pin_toggle_shadow_bit', chip._shadow[0] & 0x01, 1)

# value() read returns the shadow bit (no bus read)
p0.value(1)
check_eq('pin_value_1_shadow', chip._shadow[0] & 0x01, 1)
check_eq('pin_value_read', p0.value(), 1)
p0.value(0)
check_eq('pin_value_0_shadow', chip._shadow[0] & 0x01, 0)

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
full.clear()                    # raises RuntimeError if SRCLR not wired
check_true('clear_accepted', True)
full.set_output_enable(False)
check_true('set_output_enable_false_accepted', True)
full.set_output_enable(True)
check_true('set_output_enable_true_accepted', True)

full.write_all([0xA5, 0x5A])
check_eq('write_all_shadow_0', full._shadow[0], 0xA5)
check_eq('write_all_shadow_1', full._shadow[1], 0x5A)

# write_all pads / truncates to num_devices
full.write_all([0xFF])
check_eq('write_all_pad_shadow_0', full._shadow[0], 0xFF)
check_eq('write_all_pad_shadow_1', full._shadow[1], 0x00)
full.write_all([0x12, 0x34, 0x56])
check_eq('write_all_truncate_shadow_0', full._shadow[0], 0x12)
check_eq('write_all_truncate_shadow_1', full._shadow[1], 0x34)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
