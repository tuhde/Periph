"""TPIC6B595 hardware test — Linux kernel.

Run on host with:
    SIPO_MODE=sw GPIO_CHIP=/dev/gpiochip0 SIPO_RCK=... SIPO_SRCLR=...
    SIPO_G=... SIPO_SER_IN=... SIPO_SRCK=... python3 tpic6b595_test_linux.py

Or in hardware mode:
    SIPO_MODE=hw SIPO_SPI_BUS=0 SIPO_SPI_DEVICE=0 ...
"""
import os
import sys

try:
    import gpiod
except ImportError:
    print('FAIL gpiod_available'); sys.exit(2)

from periph.connection.sipo_linux import SiPoConnection
from periph.chips.io_expander.tpic6b595 import Tpic6b595Minimal, Tpic6b595Full

CHIP = os.environ.get('GPIO_CHIP', '/dev/gpiochip0')
MODE = os.environ.get('SIPO_MODE', 'sw')

RCK    = int(os.environ.get('SIPO_RCK',    '5'))
SRCLR  = int(os.environ.get('SIPO_SRCLR',  '6'))
G      = int(os.environ.get('SIPO_G',      '13'))
SER_IN = int(os.environ.get('SIPO_SER_IN', '19'))
SRCK   = int(os.environ.get('SIPO_SRCK',   '26'))

SPI_BUS    = int(os.environ.get('SIPO_SPI_BUS', '0'))
SPI_DEVICE = int(os.environ.get('SIPO_SPI_DEVICE', '0'))

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


offsets = [RCK, SRCLR, G]
if MODE == 'sw':
    offsets += [SER_IN, SRCK]

chip_dev = gpiod.Chip(CHIP)
settings = gpiod.LineSettings(direction=gpiod.line.Direction.OUTPUT)
request = chip_dev.request_lines(consumer='tpic6b595_test', config={o: settings for o in offsets})

if MODE == 'hw':
    connection = SiPoConnection(request, RCK, bus_num=SPI_BUS, device_num=SPI_DEVICE,
                               srclr_offset=SRCLR, g_offset=G)
else:
    connection = SiPoConnection(request, RCK, ser_in_offset=SER_IN, srck_offset=SRCK,
                               srclr_offset=SRCLR, g_offset=G)

chip = Tpic6b595Minimal(connection, num_devices=1)

# After init, shadow must be [0x00] (all outputs OFF)
check_eq('init_shadow_0', chip._shadow[0], 0x00)

# fill / off: shadow updates immediately
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
p0.value(0)
check_eq('pin_value_0_shadow', chip._shadow[0] & 0x01, 0)
check_eq('pin_value_read', p0.value(), 0)
p0.value(1)
check_eq('pin_value_1_shadow', chip._shadow[0] & 0x01, 1)

# Cascaded driver: shadow is a 2-byte buffer
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

connection.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
