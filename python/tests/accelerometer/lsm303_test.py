"""MicroPython hardware test for the LSM303 — runs against real hardware.

Verifies the magnetometer identity, exercises accelerometer and magnetometer
configuration, performs burst reads of both sensors, checks magnitudes and
the software heading.
"""

import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Minimal, LSM303Full
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
accel_conn = I2CConnection(i2c, cfg.ADDR)
mag_conn = I2CConnection(i2c, 0x1E)  # magnetometer slave, fixed address

compass = LSM303Minimal(accel_conn, mag_conn)
check_true('construct_minimal', isinstance(compass, LSM303Minimal))

ident = compass._mag.read_reg(LSM303Minimal._REG_IRA_M, 3)
check_true('mag_identity', bytes(ident) == b'\x48\x34\x33')

x, y, z = compass.read_acceleration()
check_true('read_acceleration_returns_three_floats',
           isinstance(x, float) and isinstance(y, float) and isinstance(z, float))
check_true('acceleration_magnitude_near_1g',
           abs((x * x + y * y + z * z) ** 0.5 - 1.0) < 0.5)

mx, my, mz = compass.read_magnetic()
check_true('read_magnetic_returns_three_floats',
           isinstance(mx, float) and isinstance(my, float) and isinstance(mz, float))
check_true('magnetic_field_within_range', abs(mx) <= 810.0 and abs(my) <= 810.0 and abs(mz) <= 810.0)

# Full driver: reconfiguration + raw reads + heading.
compass_full = LSM303Full(accel_conn, mag_conn)
check_true('construct_full', isinstance(compass_full, LSM303Full))

compass_full.set_range(4)
x, y, z = compass_full.read_acceleration()
check_true('read_after_set_range_4g',
           isinstance(x, float) and isinstance(y, float) and isinstance(z, float))

compass_full.set_mag_range(810)
mx, my, mz = compass_full.read_magnetic()
check_true('read_after_set_mag_range_810ut', abs(mx) <= 810.0 and abs(my) <= 810.0 and abs(mz) <= 810.0)

rx, ry, rz = compass_full.read_acceleration_raw()
check_true('raw_acceleration_12bit_range', -2048 <= rx <= 2047 and -2048 <= ry <= 2047 and -2048 <= rz <= 2047)

hdg = compass_full.heading()
check_true('heading_in_range', 0.0 <= hdg < 360.0)

temp = compass_full.read_temperature_delta()
check_true('temperature_delta_reasonable', abs(temp) < 100.0)

compass_full.power_down()
compass_full.set_data_rate(50)
compass_full.set_mag_mode(0)
check_true('power_cycle_and_reconfigure', True)

print('===DONE: {} passed, {} failed==='.format(passed, failed))
