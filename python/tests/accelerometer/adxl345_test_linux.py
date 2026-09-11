"""Linux hardware test for the ADXL345 — runs against a Raspberry Pi.

Reads DEVID, exercises the data-format / BW_RATE / POWER_CTL defaults,
performs a single 6-byte burst read, and prints the result.
"""

import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.accelerometer.adxl345 import ADXL345Minimal, ADXL345Full

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))
I2C_ADDR = int(os.environ.get('I2C_ADDR', '0x53'), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)

accel = ADXL345Minimal(connection)
check_true('construct_minimal', isinstance(accel, ADXL345Minimal))

x, y, z = accel.read()
check_true('read_returns_three_floats',
           isinstance(x, float) and isinstance(y, float) and isinstance(z, float))
check_true('read_magnitude_near_1g',
           abs((x * x + y * y + z * z) ** 0.5 - 1.0) < 0.5)

accel_full = ADXL345Full(connection)
check_true('construct_full', isinstance(accel_full, ADXL345Full))
accel_full.set_range(4)
x, y, z = accel_full.read()
check_true('read_after_set_range_4g',
           isinstance(x, float) and isinstance(y, float) and isinstance(z, float))

connection.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))