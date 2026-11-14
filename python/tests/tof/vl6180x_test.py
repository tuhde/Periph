import sys
import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.tof.vl6180x import VL6180XFull, SOURCE_RANGE, MODE_OUT_OF_WINDOW, MODE_NEW_SAMPLE_READY

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
connection = I2CConnection(i2c, cfg.ADDR, reg_bytes=2)
sensor = VL6180XFull(connection)

check_eq('model id', sensor.model_id(), 0xB4)
check_true('revision', sensor.revision()[0] >= 1)

mm = sensor.read_range()
check_true('range 0-255 mm', 0 <= mm <= 255)
mm, err = sensor.read_range_status()
check_true('range status', 0 <= mm <= 255 and 0 <= err <= 15)

raw = sensor.read_als_raw()
check_true('als raw', 0 <= raw <= 65535)
check_true('lux positive', sensor.read_lux() >= 0.0)
for code in (0, 6, 7):
    sensor.set_als_gain(code)
    check_true('lux at gain code ' + str(code), sensor.read_lux() >= 0.0)
sensor.set_als_gain(6)
sensor.set_als_integration_time(50)
check_true('lux at 50 ms', sensor.read_lux() >= 0.0)
sensor.set_als_integration_time(100)
sensor.set_als_scaler(1)

sensor.set_range_offset(3)
check_eq('offset round trip', sensor.get_range_offset(), 3)
sensor.set_range_offset(0)
sensor.set_crosstalk_rate(0.0)
sensor.set_max_convergence_time(49)
sensor.set_averaging_period(48)
sensor.recalibrate_vhv()
check_true('range after recalibration', 0 <= sensor.read_range() <= 255)

sensor.start_continuous_range(50)
for i in range(5):
    check_true('continuous range ' + str(i), 0 <= sensor.read_range_continuous() <= 255)
sensor.stop_continuous_range()
time.sleep(0.2)

sensor.set_range_thresholds(30, 80)
sensor.enable_interrupt(SOURCE_RANGE, MODE_OUT_OF_WINDOW)
sensor.start_continuous_range(50)
time.sleep(0.3)
sources = sensor.poll_interrupt()
check_true('poll_interrupt returns a source mask', 0 <= sources <= 7)
sensor.stop_continuous_range()
time.sleep(0.2)
sensor.disable_interrupt(SOURCE_RANGE)
sensor.enable_interrupt(SOURCE_RANGE, MODE_NEW_SAMPLE_READY)
sensor.poll_interrupt()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
try:
    sys.exit(0 if failed == 0 else 1)
except AttributeError:
    pass  # MicroPython has no sys.exit

