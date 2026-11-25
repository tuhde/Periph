import sys
import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.humidity.si7021 import Si7021Full

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
sensor = Si7021Full(connection)

sensor.software_reset()
check_eq('device type', sensor.device_type(), 0x15)
check_true('serial number', sensor.serial_number() > 0)
check_eq('user register after reset', sensor.read_user_register() & 0xBF, 0x3A)

t, rh = sensor.read()
check_true('temperature range', -40.0 < t < 125.0)
check_true('humidity range', 0.0 <= rh <= 100.0)
check_true('read_humidity range', 0.0 <= sensor.read_humidity() <= 100.0)
check_true('read_temperature range', -40.0 < sensor.read_temperature() < 125.0)
check_true('read_temperature_previous range', -40.0 < sensor.read_temperature_previous() < 125.0)
check_true('raw humidity range', 0 <= sensor.read_raw_humidity() <= 65535)
check_true('raw temperature range', 0 <= sensor.read_raw_temperature() <= 65535)

for pair in ((12, 14), (8, 12), (10, 13), (11, 11)):
    sensor.set_resolution(*pair)
    check_eq('resolution ' + str(pair), sensor.get_resolution(), pair)
    t, rh = sensor.read()
    check_true('read at ' + str(pair), -40.0 < t < 125.0 and 0.0 <= rh <= 100.0)
sensor.set_resolution(12, 14)
check_true('conversion time', 0.005 < sensor.conversion_time() < 0.03)

sensor.set_heater(True)
check_true('heater on', sensor.is_heater_on())
sensor.set_heater(False)
check_true('heater off', not sensor.is_heater_on())
check_true('vdd ok', not sensor.vdd_low())
check_true('firmware readable', 0 <= sensor.firmware_revision() <= 0xFF)

dp = sensor.dew_point()
check_true('dew point plausible', -60.0 < dp < 60.0)
sensor.software_reset()
check_true('read after software reset', 0.0 <= sensor.read()[1] <= 100.0)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
try:
    sys.exit(0 if failed == 0 else 1)
except AttributeError:
    pass  # MicroPython has no sys.exit
