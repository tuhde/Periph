import sys
import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.color.as7262 import AS7262Full

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
sensor = AS7262Full(connection)

check_eq('device type', sensor.device_type(), 0x40)
check_eq('hardware version', sensor.hardware_version(), (0x40, 0x3E))
check_true('temperature range', -40.0 <= sensor.temperature() <= 125.0)

d = sensor.read()
check_true('calibrated channels', len(d) == 6 and all(v >= 0.0 for v in d.values()))
r = sensor.read_raw()
check_true('raw channels', all(0 <= v <= 65535 for v in r.values()))
check_true('read_channel raw', 0 <= sensor.read_channel(2, False) <= 65535)

sensor.set_gain(64)
check_eq('gain', sensor.get_gain(), 64.0)
sensor.set_gain(16)
sensor.set_integration_time(50)
check_true('integration time', abs(sensor.get_integration_time() - 50.4) < 0.01)
sensor.set_mode(2)
check_eq('mode', sensor.get_mode(), 2)
sensor.set_mode(3)

sensor.measure()
time.sleep(0.6)
check_true('data ready', sensor.data_ready())
check_true('last calibrated', len(sensor.read_calibrated()) == 6)

sensor.set_indicator_led(True, 4)
sensor.set_indicator_led(False)
sensor.soft_reset()
check_eq('reset int_t', sensor.get_integration_time(), 255 * 2.8)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
try:
    sys.exit(0 if failed == 0 else 1)
except AttributeError:
    pass  # MicroPython has no sys.exit
