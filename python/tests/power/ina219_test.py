import time
import _testconfig as cfg
from periph.transport.i2c_micropython import I2CTransport
from periph.chips.power.ina219 import INA219Full

from machine import I2C, Pin

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


i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
transport = I2CTransport(i2c, cfg.ADDR)
ina = INA219Full(transport)

check_true('voltage non-negative', ina.voltage()       >= 0.0)
check_true('shunt_voltage finite', ina.shunt_voltage() > -1.0)
check_true('current finite',       ina.current()       > -10.0)
check_true('power non-negative',   ina.power()         >= 0.0)

check_true('conversion_ready', ina.conversion_ready())
check_true('no overflow',      not ina.overflow())

ina.configure(1, 3, 3, 3, 7)
check_true('voltage after configure', ina.voltage() >= 0.0)

ina.shutdown()
time.sleep_ms(1)
ina.wake()
check_true('wake: voltage non-negative', ina.voltage() >= 0.0)

ina.reset()
check_true('reset: voltage non-negative', ina.voltage() >= 0.0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
