import sys
import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.humidity.hs40xx import HS40xxFull, HS4001Full, HS4003Full

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
sensor = HS40xxFull(connection)

d = sensor.read()
check_true('temperature range', -40.0 <= d['temperature_c'] <= 125.0)
check_true('humidity range', 0.0 <= d['humidity_pct'] <= 100.0)
check_true('read_temperature range', -40.0 <= sensor.read_temperature() <= 125.0)
check_true('read_humidity range', 0.0 <= sensor.read_humidity() <= 100.0)
rh_raw, t_raw = sensor.read_raw()
check_true('raw ranges', 0 <= rh_raw <= 16383 and 0 <= t_raw <= 16383)
check_true('id nonzero', sensor.read_id() != 0)

sensor.set_resolution(12, 10)
check_eq('resolution', sensor.get_resolution(), (12, 10))
sensor.set_resolution(14, 14)
check_eq('resolution default', sensor.get_resolution(), (14, 14))

sensor.start_periodic(0.5)
time.sleep(1.0)
p = sensor.read_periodic()
check_true('periodic humidity', 0.0 <= p['humidity_pct'] <= 100.0)
sensor.stop_periodic()
sensor.soft_reset()

dp = sensor.dew_point()
check_true('dew point plausible', -60.0 < dp < 60.0)

check_true('HS4001 alias', HS4001Full is HS40xxFull)
check_true('HS4003 alias', HS4003Full is HS40xxFull)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
try:
    sys.exit(0 if failed == 0 else 1)
except AttributeError:
    pass  # MicroPython has no sys.exit
