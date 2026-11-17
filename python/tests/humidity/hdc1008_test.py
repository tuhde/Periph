import sys
import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.humidity.hdc1008 import HDC1008Full

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
sensor = HDC1008Full(connection)

sensor.init()
check_eq('config after init', sensor.read_config() & 0xFF00, 0x1000)

t, rh = sensor.read()
check_true('temperature range', -40.0 < t < 125.0)
check_true('humidity range', 0.0 <= rh <= 100.0)
check_true('read_humidity range', 0.0 <= sensor.read_humidity() <= 100.0)
check_true('read_temperature range', -40.0 < sensor.read_temperature() < 125.0)
rt, rr = sensor.read_raw()
check_true('raw ranges', 0 <= rt <= 65535 and 0 <= rr <= 65535)

for tb, hb in ((14, 14), (11, 11), (11, 8), (14, 8)):
    sensor.set_resolution(tb, hb)
    t, rh = sensor.read()
    check_true('read at %d/%d bit' % (tb, hb), -40.0 < t < 125.0 and 0.0 <= rh <= 100.0)
sensor.set_resolution(14, 14)

sensor.set_heater(True)
check_true('heater on', sensor.is_heater_on())
sensor.set_heater(False)
check_true('heater off', not sensor.is_heater_on())
check_true('battery ok', not sensor.battery_low())
check_true('serial id nonzero', sensor.serial_id() != 0)
check_true('conversion time', 0.005 < sensor.conversion_time() < 0.02)
sensor.software_reset()
check_eq('config after reset', sensor.read_config() & 0xFF00, 0x1000)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
try:
    sys.exit(0 if failed == 0 else 1)
except AttributeError:
    pass  # MicroPython has no sys.exit
