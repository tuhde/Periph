import os
import sys
import time

import gpiod
from gpiod.line import Direction, Value
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


request = gpiod.request_lines(
    CHIP,
    consumer='hx711_chip_test',
    config={
        DOUT:   gpiod.LineSettings(direction=Direction.INPUT),
        PD_SCK: gpiod.LineSettings(direction=Direction.OUTPUT,
                                   output_value=Value.INACTIVE),
    },
)

transport = HX711Transport(request, DOUT, PD_SCK)
chip = HX711Full(transport)
time.sleep(0.01)

check_true('is_ready returns bool', isinstance(chip.is_ready(), bool))

raw = chip.read_raw()
time.sleep(0.01)
check_true('read_raw returns int', isinstance(raw, int))
check_true('read_raw in 24-bit signed range', -8388608 <= raw <= 8388607)

chip.set_gain(128)
time.sleep(0.01)
check_true('set_gain(128) accepted', True)

chip.set_gain(64)
time.sleep(0.01)
check_true('set_gain(64) accepted', True)

chip.set_gain(32)
time.sleep(0.01)
check_true('set_gain(32) accepted', True)

chip.set_gain(128)
time.sleep(0.01)

try:
    chip.set_gain(99)
    check_true('set_gain(99) raises ValueError', False)
except ValueError:
    check_true('set_gain(99) raises ValueError', True)

avg = chip.read_average(3)
time.sleep(0.01)
check_true('read_average returns int', isinstance(avg, int))
check_true('read_average in 24-bit signed range', -8388608 <= avg <= 8388607)

chip.tare(3)
time.sleep(0.01)
check_true('tare accepted', True)

offset = chip.get_offset()
check_true('get_offset returns int', isinstance(offset, int))

chip.set_scale(420.0)
check_true('set_scale accepted', True)

scale = chip.get_scale()
check_true('get_scale returns float', isinstance(scale, float))
check_true('get_scale returns 420.0', scale == 420.0)

weight = chip.read_weight(1)
time.sleep(0.01)
check_true('read_weight returns float', isinstance(weight, float))

chip.power_down()
check_true('power_down accepted', True)

chip.power_up()
time.sleep(0.01)
check_true('power_up accepted', True)

chip.power_down()
check_true('final power_down accepted', True)

transport.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
