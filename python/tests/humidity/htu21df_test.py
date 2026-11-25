import sys
import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.humidity.htu21df import HTU21DFFull

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
sensor = HTU21DFFull(connection)

sensor.init()
check_eq('user register after reset', sensor.read_user_register() & 0x83, 0x02)

t, rh = sensor.read()
check_true('temperature range', -40.0 < t < 125.0)
check_true('humidity range', -6.0 <= rh <= 119.0)
check_true('read_humidity range', -6.0 <= sensor.read_humidity() <= 119.0)
check_true('read_temperature range', -40.0 < sensor.read_temperature() < 125.0)
check_true('compensated humidity range', -6.0 <= sensor.read_humidity_compensated() <= 125.0)
rt, rr = sensor.read_raw()
check_true('raw ranges', 0 <= rt <= 65535 and 0 <= rr <= 65535)

for res in (0, 1, 2, 3):
    sensor.set_resolution(res)
    check_eq('resolution ' + str(res), sensor.get_resolution(), res)
    t, rh = sensor.read()
    check_true('read at res ' + str(res), -40.0 < t < 125.0 and -6.0 <= rh <= 119.0)
sensor.set_resolution(0)

sensor.set_heater(True)
check_true('heater on', sensor.heater())
sensor.set_heater(False)
check_true('heater off', not sensor.heater())
check_true('battery ok', not sensor.battery_low())

dp = sensor.dew_point()
check_true('dew point plausible', -60.0 < dp < 60.0)
sensor.soft_reset()
check_true('read after soft reset', -6.0 <= sensor.read()[1] <= 119.0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
try:
    sys.exit(0 if failed == 0 else 1)
except AttributeError:
    pass  # MicroPython has no sys.exit
