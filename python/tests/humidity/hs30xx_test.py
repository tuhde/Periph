import sys
import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.humidity.hs30xx import HS30xxFull, HS3001Full, HS3002Full, HS3003Full

from machine import I2C, Pin

passed = 0
failed = 0


def check_eq(label, got, expected):
    global passed, failed
    if got == expected:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL {}: got {!r}, expected {!r}'.format(label, got, expected))
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
sensor = HS30xxFull(connection)

d = sensor.read()
check_true('temperature range', -40.0 <= d['temperature_c'] <= 125.0)
check_true('humidity range', 0.0 <= d['humidity_pct'] <= 100.0)
check_true('read_temperature range', -40.0 <= sensor.read_temperature() <= 125.0)
check_true('read_humidity range', 0.0 <= sensor.read_humidity() <= 100.0)
rh_raw, t_raw, status = sensor.read_raw()
check_true('raw ranges', 0 <= rh_raw <= 16383 and 0 <= t_raw <= 16383)
check_eq('raw status valid', status, 0)

sensor.set_measurement_wait(60)
check_true('read with 60 ms wait', 0.0 <= sensor.read_humidity() <= 100.0)
sensor.set_measurement_wait(40)

dp = sensor.dew_point()
check_true('dew point plausible', -60.0 < dp < 60.0)

check_true('HS3001 alias', HS3001Full is HS30xxFull)
check_true('HS3002 alias', HS3002Full is HS30xxFull)
check_true('HS3003 alias', HS3003Full is HS30xxFull)

# NVM access (resolution, ID) needs a power-cycle helper and is not exercised here.

print('===DONE: {} passed, {} failed==='.format(passed, failed))
try:
    sys.exit(0 if failed == 0 else 1)
except AttributeError:
    pass  # MicroPython has no sys.exit
