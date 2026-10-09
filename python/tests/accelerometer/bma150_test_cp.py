"""CircuitPython hardware test for the BMA150 — runs against real hardware.

Same coverage as the MicroPython test, but using busio.I2C and time.sleep.
"""

import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.accelerometer.bma150 import BMA150Minimal, BMA150Full

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


i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
i2c.try_lock()
i2c.unlock()
connection = I2CConnection(i2c, cfg.ADDR)

accel = BMA150Minimal(connection)
check_true('construct_minimal', isinstance(accel, BMA150Minimal))

x, y, z = accel.read()
check_true('read_returns_three_floats',
           isinstance(x, float) and isinstance(y, float) and isinstance(z, float))
check_true('read_magnitude_near_1g',
           abs((x * x + y * y + z * z) ** 0.5 - 1.0) < 0.5)

accel_full = BMA150Full(connection)
check_true('construct_full', isinstance(accel_full, BMA150Full))
accel_full.set_range(4)
x, y, z = accel_full.read()
check_true('read_after_set_range_4g',
           isinstance(x, float) and isinstance(y, float) and isinstance(z, float))

temp = accel_full.read_temperature()
check_true('temperature_in_range', -30.0 <= temp <= 97.5)

connection.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
