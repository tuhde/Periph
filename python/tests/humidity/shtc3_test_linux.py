import os
import time
from periph.connection.i2c_linux import I2CConnection
from periph.chips.humidity.shtc3 import SHTC3Full

passed = 0
failed = 0

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x70'), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)

def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


sensor = SHTC3Full(connection)
sensor.init()
m = sensor.read()
check_true('read_temperature_range', -45 < m['temperature_c'] < 130)
check_true('read_humidity_range', 0 <= m['humidity_pct'] <= 100)
check_true('read_humidity_single', 0 <= sensor.read_humidity() <= 100)
check_true('read_temperature_single', -45 < sensor.read_temperature() < 130)
rt, rr = sensor.read_raw()
check_true('read_raw_range', 0 <= rt <= 65535 and 0 <= rr <= 65535)
check_true('dew_point_below_temperature', sensor.dew_point() <= sensor.read_temperature() + 1)
check_true('read_id_product_code', (sensor.read_id() & 0x083F) == 0x0807)
sensor.set_low_power(True)
r = sensor.read()
check_true('low_power_read', 0 <= r['humidity_pct'] <= 100 and -45 < r['temperature_c'] < 130)
sensor.set_low_power(False)
sensor.set_sleep_between(False)
check_true('read_without_sleep', 0 <= sensor.read()['humidity_pct'] <= 100)
check_true('read_without_sleep_again', 0 <= sensor.read()['humidity_pct'] <= 100)
sensor.sleep()
sensor.wake()
sensor.set_sleep_between(True)
sensor.set_clock_stretching(True)
check_true('read_with_clock_stretching', 0 <= sensor.read()['humidity_pct'] <= 100)
sensor.set_clock_stretching(False)
sensor.soft_reset()
check_true('read_after_reset', 0 <= sensor.read()['humidity_pct'] <= 100)

print('===DONE: %d passed, %d failed===' % (passed, failed))
connection.close()
