import os
import sys
import time

import gpiod
from gpiod.line import Direction, Value
from periph.connection.hx711_linux import HX711Connection
from periph.chips.adc_dac.hx710a import HX710AFull

CHIP   = os.environ.get('GPIO_CHIP',    '/dev/gpiochip0')
DOUT   = int(os.environ.get('HX710A_DOUT',   '5'))
PD_SCK = int(os.environ.get('HX710A_PD_SCK', '6'))

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
    consumer='hx710a_chip_test',
    config={
        DOUT:   gpiod.LineSettings(direction=Direction.INPUT),
        PD_SCK: gpiod.LineSettings(direction=Direction.OUTPUT,
                                   output_value=Value.INACTIVE),
    },
)

connection = HX711Connection(request, DOUT, PD_SCK)
chip = HX710AFull(connection)
time.sleep(0.01)

check_true('is_ready returns bool', isinstance(chip.is_ready(), bool))

# Exercise power_down()/power_up() early (conformance's powerdown_pulse
# check captures this call - see conformance/adc_dac/hx710a_conformance.py).
chip.power_down()
time.sleep(0.001)
chip.power_up()
time.sleep(0.5)  # settling time after power-up before the next conversion
check_true('power_down_then_power_up_accepted', True)

raw = chip.read_raw()
time.sleep(0.01)
check_true('read_raw returns int', isinstance(raw, int))
check_true('read_raw in 24-bit signed range', -8388608 <= raw <= 8388607)

chip.set_rate(40)
time.sleep(0.1)  # settling time after rate change at 40 SPS
check_true('set_rate(40) accepted', True)

chip.set_rate(10)
time.sleep(0.4)  # settling time after rate change at 10 SPS
check_true('set_rate(10) accepted', True)

try:
    chip.set_rate(20)
    check_true('set_rate(20) raises ValueError', False)
except ValueError:
    check_true('set_rate(20) raises ValueError', True)

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

temp_raw = chip.read_temperature_raw()
time.sleep(0.01)
check_true('read_temperature_raw returns int', isinstance(temp_raw, int))
check_true('read_temperature_raw in 24-bit signed range', -8388608 <= temp_raw <= 8388607)

connection.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
