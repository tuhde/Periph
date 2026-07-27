import os
import sys

from periph.connection.i2c_linux import I2CConnection
from periph.chips.power.ina219 import INA219Full
import time

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x40'), 16)

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


connection = I2CConnection(I2C_BUS, I2C_ADDR)
ina = INA219Full(connection)

check_true('voltage non-negative', ina.voltage()       >= 0.0)
check_true('shunt_voltage finite', ina.shunt_voltage() > -1.0)
check_true('current finite',       ina.current()       > -10.0)
check_true('power non-negative',   ina.power()         >= 0.0)

check_true('conversion_ready', ina.conversion_ready())
check_true('no overflow',      not ina.overflow())

ina.configure(brng=1, pga=3, badc=0x03, sadc=0x03, mode=7)
check_true('after configure: voltage non-negative', ina.voltage() >= 0.0)

ina.shutdown()
time.sleep(0.001)
ina.wake()
check_true('wake: voltage non-negative', ina.voltage() >= 0.0)

ina.reset()
check_true('after reset: voltage non-negative', ina.voltage() >= 0.0)

connection.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
