import os
import sys

import gpiod
from periph.transport.hx711_linux import HX711Transport

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


chip   = gpiod.Chip(CHIP)
dout   = chip.get_line(DOUT)
pd_sck = chip.get_line(PD_SCK)
dout.request(consumer='hx711_test', type=gpiod.LINE_REQ_DIR_IN)
pd_sck.request(consumer='hx711_test', type=gpiod.LINE_REQ_DIR_OUT)

transport = HX711Transport(dout, pd_sck)

check_true('is_ready returns bool', isinstance(transport.is_ready(), bool))

val = transport.read_raw(25)
check_true('read_raw(25) returns int', isinstance(val, int))
check_true('read_raw(25) in 24-bit signed range', -8388608 <= val <= 8388607)

val = transport.read_raw(26)
check_true('read_raw(26) returns int', isinstance(val, int))
check_true('read_raw(26) in 24-bit signed range', -8388608 <= val <= 8388607)

val = transport.read_raw(27)
check_true('read_raw(27) returns int', isinstance(val, int))
check_true('read_raw(27) in 24-bit signed range', -8388608 <= val <= 8388607)

try:
    transport.read_raw(24)
    check_true('read_raw(24) raises ValueError', False)
except ValueError:
    check_true('read_raw(24) raises ValueError', True)

transport.power_down()
check_true('power_down accepted', True)

transport.power_up()
check_true('power_up accepted', True)

transport.close()
check_true('close accepted', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
