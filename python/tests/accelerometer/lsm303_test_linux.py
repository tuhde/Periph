"""Linux hardware test for the LSM303DLHC — runs against a Raspberry Pi.

Constructs the Minimal and Full drivers on one I²C bus (accelerometer 0x19,
magnetometer 0x1E), checks the magnetometer identity, reads acceleration,
magnetic field, heading and temperature, exercises a few Full configuration
calls, and prints PASS/FAIL per check.
"""

import math
import os
import time
from periph.connection.i2c_linux import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Minimal, LSM303Full, LSM303Source

ACCEL_ADDR = 0x19
MAG_ADDR = 0x1E

I2C_BUS = int(os.environ.get('LINUX_I2C_BUS', '1'))

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


accel_conn = I2CConnection(I2C_BUS, ACCEL_ADDR)
mag_conn = I2CConnection(I2C_BUS, MAG_ADDR)

chip = LSM303Minimal(accel_conn, mag_conn)
check_true('construct_minimal', isinstance(chip, LSM303Minimal))

time.sleep(0.1)

ax, ay, az = chip.read_acceleration()
check_true('read_acceleration_floats', is_triple((ax, ay, az)))
check_true('read_acceleration_magnitude_near_1g',
           abs(math.sqrt(ax * ax + ay * ay + az * az) - 1.0) < 0.5)

mx, my, mz = chip.read_magnetic()
check_true('read_magnetic_floats', is_triple((mx, my, mz)))

full = LSM303Full(accel_conn, mag_conn)
check_true('construct_full', isinstance(full, LSM303Full))
check_true('mag_identity', full.read_mag_identity() == b'\x48\x34\x33')

full.set_range(4)
full.set_data_rate(50)
full.set_mag_range(130)
full.set_mag_data_rate(15)
time.sleep(0.2)

ax, ay, az = full.read_acceleration()
check_true('read_after_set_range_4g', is_triple((ax, ay, az)))
check_true('read_acceleration_raw_ints',
           all(isinstance(c, int) for c in full.read_acceleration_raw()))

h = full.heading()
check_true('heading_in_range', 0.0 <= h < 360.0)

t = full.read_temperature_delta()
check_true('temperature_delta_finite', isinstance(t, float) and abs(t) < 200.0)

full.set_fifo(2, watermark=16)
time.sleep(0.2)
check_true('read_fifo_list', isinstance(full.read_fifo(), list))
full.set_fifo(0)

full.enable_interrupt(LSM303Source.SOURCE_DATA_READY)
full.disable_interrupt(LSM303Source.SOURCE_DATA_READY)
check_true('poll_interrupt_int', isinstance(full.poll_interrupt(), int))

accel_conn.close()
mag_conn.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
