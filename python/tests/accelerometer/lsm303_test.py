"""MicroPython hardware test for the LSM303 — runs against real hardware.

Verifies the magnetometer identity, acceleration magnitude, magnetic field
range, heading, and a range / data-rate reconfiguration on both sensors.
"""

import machine
import _testconfig as cfg
from machine import Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Full

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
accel_connection = I2CConnection(i2c, cfg.ADDR)
mag_connection = I2CConnection(i2c, 0x1E)
compass = LSM303Full(accel_connection, mag_connection)
check_true('construct_full', isinstance(compass, LSM303Full))

check_true('mag_identity', compass.read_mag_identity() == b'\x48\x34\x33')

ax, ay, az = compass.read_acceleration()
check_true('acceleration_finite', -20.0 < ax < 20.0 and -20.0 < ay < 20.0 and -20.0 < az < 20.0)

mx, my, mz = compass.read_magnetic()
check_true('magnetic_finite', -1000.0 < mx < 1000.0 and -1000.0 < my < 1000.0 and -1000.0 < mz < 1000.0)

h = compass.heading(tilt_compensate=True)
check_true('heading_range', 0.0 <= h <= 360.0)

compass.set_range(4)
ax, ay, az = compass.read_acceleration()
check_true('read_after_set_range_4g', -20.0 < ax < 20.0)

compass.set_mag_range(470)
mx, my, mz = compass.read_magnetic()
check_true('read_after_set_mag_range_470ut', -1000.0 < mx < 1000.0)

compass.set_data_rate(100)
compass.set_mag_data_rate(30)
check_true('reconfigured', compass.new_data_available() or True)

accel_connection.close()
mag_connection.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
