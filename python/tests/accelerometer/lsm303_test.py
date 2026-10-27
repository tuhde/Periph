"""MicroPython hardware test for the LSM303DLHC — runs against real hardware.

Constructs the Minimal and Full drivers on one I²C bus (accelerometer 0x19,
magnetometer 0x1E), checks the magnetometer identity, reads acceleration,
magnetic field and temperature, exercises a few Full configuration calls,
and prints PASS/FAIL per check.
"""

import math
import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Minimal, LSM303Full, LSM303Source

from machine import I2C, Pin

ACCEL_ADDR = 0x19
MAG_ADDR = 0x1E

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


def is_triple(v):
    return len(v) == 3 and all(isinstance(c, float) for c in v)


i2c = I2C(cfg.I2C_ID, sda=Pin(cfg.SDA), scl=Pin(cfg.SCL), freq=cfg.FREQ)
accel_conn = I2CConnection(i2c, ACCEL_ADDR)
mag_conn = I2CConnection(i2c, MAG_ADDR)

chip = LSM303Minimal(accel_conn, mag_conn)
check_true('construct_minimal', isinstance(chip, LSM303Minimal))

# The init sequence waits for the first accelerometer sample; poll briefly.
time.sleep(0.1)

ax, ay, az = chip.read_acceleration()
check_true('read_acceleration_floats', is_triple((ax, ay, az)))
mag_g = math.sqrt(ax * ax + ay * ay + az * az)
check_true('read_acceleration_magnitude_near_1g', abs(mag_g - 1.0) < 0.5)

mx, my, mz = chip.read_magnetic()
check_true('read_magnetic_floats', is_triple((mx, my, mz)))
check_true('read_magnetic_plausible', math.sqrt(mx * mx + my * my + mz * mz) < 1000.0)

full = LSM303Full(accel_conn, mag_conn)
check_true('construct_full', isinstance(full, LSM303Full))
check_true('full_identity', full.read_mag_identity() == b'\x48\x34\x33')

full.set_range(4)
full.set_data_rate(50)
full.set_mag_range(130)
full.set_mag_data_rate(15)
time.sleep(0.2)

ax, ay, az = full.read_acceleration()
check_true('read_after_config', is_triple((ax, ay, az)))
raw = full.read_acceleration_raw()
check_true('read_acceleration_raw_ints', len(raw) == 3 and all(isinstance(c, int) for c in raw))

h = full.heading()
check_true('heading_in_range', 0.0 <= h < 360.0)
check_true('heading_flat_in_range', 0.0 <= full.heading(tilt_compensate=False) < 360.0)

t = full.read_temperature_delta()
check_true('temperature_delta_finite', isinstance(t, float) and abs(t) < 200.0)

full.set_fifo(2, watermark=16)
time.sleep(0.2)
samples = full.read_fifo()
check_true('read_fifo_list', isinstance(samples, list))
full.set_fifo(0)

check_true('poll_interrupt_int', isinstance(full.poll_interrupt(), int))
full.enable_interrupt(LSM303Source.SOURCE_DATA_READY)
full.disable_interrupt(LSM303Source.SOURCE_DATA_READY)
check_true('new_data_flag_bool', isinstance(full.new_data_available(), bool))

accel_conn.close()
mag_conn.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
