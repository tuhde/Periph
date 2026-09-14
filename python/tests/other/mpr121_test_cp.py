"""CircuitPython HIL test for the MPR121."""

import sys
import time

from periph.chips.other.mpr121 import Mpr121Full
from periph.connection.i2c_auto import I2CConnection

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


connection = I2CConnection(0x5A)
mpr = Mpr121Full(connection)

t = mpr.touched()
check_true('touched in 0..4095', 0 <= t <= 0xFFF)
check_true('is_touched(0) is bool', isinstance(mpr.is_touched(0), bool))

f0 = mpr.filtered(0)
check_true('filtered(0) in 0..1023', 0 <= f0 <= 1023)

b0 = mpr.baseline(0)
check_true('baseline(0) in 0..1023', 0 <= b0 <= 1023)

mpr.configure_thresholds(0, touch=15, release=8)
mpr.configure_all_thresholds(touch=12, release=6)
mpr.configure_proximity_thresholds(touch=8, release=4)
mpr.configure_baseline_filter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0)
mpr.configure_sampling(cdc=16, cdt=1, ffi=0, sfi=0, esi=4)
mpr.configure_debounce(touch=1, release=1)
check_true('configuration methods accepted', True)

mpr.poll_interrupt()
mpr.off_interrupt()
mpr.reset()
check_true('reset completed', True)

connection.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
try:
    sys.exit(0 if failed == 0 else 1)
except AttributeError:
    pass
