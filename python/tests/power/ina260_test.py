import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.power.ina260 import INA260Full

from machine import I2C, Pin

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


i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)
ina = INA260Full(connection)

check_eq('manufacturer_id', ina.manufacturer_id(), 0x5449)
check_eq('die_id',          ina.die_id() & 0xFFF0, 0x2270)

check_true('voltage non-negative', ina.voltage()       >= 0.0)
check_true('current finite',       ina.current()       > -40.0)
check_true('power non-negative',   ina.power()         >= 0.0)

check_true('conversion_ready', ina.conversion_ready())
check_true('no overflow',      not ina.overflow())

ina.configure(INA260Full.AVG_64, INA260Full.CT_1100US, INA260Full.CT_1100US, INA260Full.MODE_SHUNT_BUS_CONT)
check_eq('configure: mfr_id still valid', ina.manufacturer_id(), 0x5449)

ina.set_alert(INA260Full.POL, 1.0, False, True)
check_true('set_alert POL: LEN bit set', (ina.alert_flags() & 0x0001) != 0)

ina.shutdown()
time.sleep_ms(1)
ina.wake()
check_true('wake: voltage non-negative', ina.voltage() >= 0.0)

ina.reset()
check_eq('reset: mfr_id still valid', ina.manufacturer_id(), 0x5449)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
