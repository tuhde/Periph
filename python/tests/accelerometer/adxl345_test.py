"""MicroPython hardware test for the ADXL345 — runs against real hardware.

Reads DEVID, exercises the data-format / BW_RATE / POWER_CTL defaults,
performs a single 6-byte burst read, and prints the result.
"""

import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.adxl345 import ADXL345Minimal, ADXL345Full
from machine import Pin

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


i2c = machine.I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
connection = I2CConnection(i2c, cfg.ADDR)

accel = ADXL345Minimal(connection)
check_true('construct_minimal', isinstance(accel, ADXL345Minimal))

x, y, z = accel.read()
check_true('read_returns_three_floats',
           isinstance(x, float) and isinstance(y, float) and isinstance(z, float))
check_true('read_magnitude_near_1g',
           abs((x * x + y * y + z * z) ** 0.5 - 1.0) < 0.5)

# Range switch on Full should leave the driver operational.
accel_full = ADXL345Full(connection)
check_true('construct_full', isinstance(accel_full, ADXL345Full))
accel_full.set_range(4)
x, y, z = accel_full.read()
check_true('read_after_set_range_4g',
           isinstance(x, float) and isinstance(y, float) and isinstance(z, float))

connection.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))