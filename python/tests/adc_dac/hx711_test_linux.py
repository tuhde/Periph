import os
import sys

import gpiod
from periph.transport.hx711_linux import HX711Transport
from periph.chips.adc_dac.hx711 import HX711Full

CHIP   = os.environ.get('GPIO_CHIP',   '/dev/gpiochip0')
DOUT   = int(os.environ.get('HX711_DOUT',   '5'))
PD_SCK = int(os.environ.get('HX711_PD_SCK', '6'))

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


chip_dev = gpiod.Chip(CHIP)
dout_line   = chip_dev.get_line(DOUT)
pd_sck_line = chip_dev.get_line(PD_SCK)
dout_line.request(consumer='hx711_chip_test', type=gpiod.LINE_REQ_DIR_IN)
pd_sck_line.request(consumer='hx711_chip_test', type=gpiod.LINE_REQ_DIR_OUT)

transport = HX711Transport(dout_line, pd_sck_line)
chip = HX711Full(transport)

check_true('is_ready returns bool', isinstance(chip.is_ready(), bool))

raw = chip.read_raw()
check_true('read_raw returns int', isinstance(raw, int))
check_true('read_raw in 24-bit signed range', -8388608 <= raw <= 8388607)

chip.set_gain(128)
check_true('set_gain(128) accepted', True)

chip.set_gain(64)
check_true('set_gain(64) accepted', True)

chip.set_gain(32)
check_true('set_gain(32) accepted', True)

chip.set_gain(128)

try:
    chip.set_gain(99)
    check_true('set_gain(99) raises ValueError', False)
except ValueError:
    check_true('set_gain(99) raises ValueError', True)

avg = chip.read_average(3)
check_true('read_average returns int', isinstance(avg, int))
check_true('read_average in 24-bit signed range', -8388608 <= avg <= 8388607)

chip.tare(3)
check_true('tare accepted', True)

offset = chip.get_offset()
check_true('get_offset returns int', isinstance(offset, int))

chip.set_scale(420.0)
check_true('set_scale accepted', True)

scale = chip.get_scale()
check_true('get_scale returns float', isinstance(scale, float))
check_true('get_scale returns 420.0', scale == 420.0)

weight = chip.read_weight(1)
check_true('read_weight returns float', isinstance(weight, float))

chip.power_down()
check_true('power_down accepted', True)

chip.power_up()
check_true('power_up accepted', True)

transport.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
