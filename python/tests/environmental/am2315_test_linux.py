import os
import sys
import time
from periph.connection.i2c_linux import I2CConnection
from periph.chips.environmental.am2315 import AM2315Full

I2C_BUS  = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x5C'), 16)

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


connection = I2CConnection(I2C_BUS, I2C_ADDR)
sensor = AM2315Full(connection)

info = sensor.read_device_info()
check_true('device info model', info['model'] != 0xFFFF)

r = sensor.read()
check_true('temperature range', -40.0 <= r['temperature_c'] <= 125.0)
check_true('humidity range', 0.0 <= r['humidity_pct'] <= 100.0)
check_true('read_humidity range', 0.0 <= sensor.read_humidity() <= 100.0)
check_true('read_temperature range', -40.0 <= sensor.read_temperature() <= 125.0)
rh_raw, t_raw = sensor.read_raw()
check_true('raw ranges', 0 <= rh_raw <= 1000 and 0 <= (t_raw & 0x7FFF) <= 1250)
f = sensor.read_fresh()
check_true('read_fresh range', -40.0 <= f['temperature_c'] <= 125.0 and 0.0 <= f['humidity_pct'] <= 100.0)
check_eq('read_registers length', len(sensor.read_registers(0x00, 4)), 4)

sensor.write_user_registers(0x1234, 0xABCD)
check_eq('user registers round trip', sensor.read_user_registers(), (0x1234, 0xABCD))
sensor.write_user_registers(0, 0)
sensor.wake()

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
