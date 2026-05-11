import os
import sys

from periph.transport.i2c_linux import I2CTransport
from periph.chips.power.ina226 import INA226Full
import time

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x40'), 16)

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got 0x{:04X}, expected 0x{:04X}'.format(label, got, expected))
        failed += 1


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


transport = I2CTransport(I2C_BUS, I2C_ADDR)
ina = INA226Full(transport)

check_eq('manufacturer_id', ina.manufacturer_id(), 0x5449)
check_eq('die_id',          ina.die_id(),          0x2260)

check_true('voltage non-negative', ina.voltage()       >= 0.0)
check_true('shunt_voltage finite', ina.shunt_voltage() > -1.0)
check_true('current finite',       ina.current()       > -10.0)
check_true('power non-negative',   ina.power()         >= 0.0)

check_true('conversion_ready', ina.conversion_ready())
check_true('no overflow',      not ina.overflow())

ina.configure(3, 4, 4, 7)
check_eq('configure: mfr_id still valid', ina.manufacturer_id(), 0x5449)

ina.set_alert(INA226Full.POL, 1.0, False, True)
check_true('set_alert POL: LEN bit set', (ina.alert_flags() & 0x0001) != 0)

ina.shutdown()
time.sleep(0.001)
ina.wake()
check_true('wake: voltage non-negative', ina.voltage() >= 0.0)

ina.reset()
check_eq('reset: mfr_id still valid', ina.manufacturer_id(), 0x5449)

transport.close()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
