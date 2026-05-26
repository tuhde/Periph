import _testconfig as cfg
from machine import Pin
from periph.transport.hx711_micropython import HX711Transport
from periph.chips.adc_dac.hx711 import HX711Full

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


dout   = Pin(cfg.DOUT,   Pin.IN)
pd_sck = Pin(cfg.PD_SCK, Pin.OUT)
transport = HX711Transport(dout, pd_sck)
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

print('===DONE: {} passed, {} failed==='.format(passed, failed))
