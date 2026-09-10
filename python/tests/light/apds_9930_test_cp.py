"""CircuitPython HIL test for the APDS-9930."""

import sys
import time

from periph.chips.light.apds_9930 import APDS9930Full
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


connection = I2CConnection(0x39)
apds = APDS9930Full(connection)

time.sleep(0.110)

st = apds.status()
check_true('status has avalid bool', isinstance(st.get('avalid'), bool))
check_true('status has pvalid bool', isinstance(st.get('pvalid'), bool))

lx = apds.lux()
check_true('lux is float', isinstance(lx, float))
check_true('lux >= 0', lx >= 0)

p = apds.proximity()
check_true('proximity >= 0', p >= 0)

c0 = apds.ch0()
c1 = apds.ch1()
check_true('ch0 >= 0', c0 >= 0)
check_true('ch1 >= 0', c1 >= 0)

apds.configure_als(0xDB, 0, False)
apds.configure_proximity(8, 0, 0, False, 0xFF)
apds.disable_wait()
apds.set_als_thresholds(0, 65535, 1)
apds.set_proximity_thresholds(0, 1023, 1)
apds.set_proximity_offset(0)
apds.sleep_after_interrupt(False)
apds.clear_interrupt('both')
check_true('config methods accepted', True)

connection.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
try:
    sys.exit(0 if failed == 0 else 1)
except AttributeError:
    pass