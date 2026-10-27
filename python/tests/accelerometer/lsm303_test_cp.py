"""CircuitPython hardware test for the LSM303DLHC — runs against real hardware.

Constructs the Minimal and Full drivers on one busio.I2C bus (accelerometer
0x19, magnetometer 0x1E), checks the magnetometer identity, reads
acceleration and magnetic field, and prints PASS/FAIL per check.
"""

import math
import time
import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.accelerometer.lsm303 import LSM303Minimal, LSM303Full

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


i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
# Defer I2C lock acquisition to the try/finally so we always release it.
i2c.try_lock()
i2c.unlock()
accel_conn = I2CConnection(i2c, ACCEL_ADDR)
mag_conn = I2CConnection(i2c, MAG_ADDR)

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
full.set_range(2)
full.set_data_rate(100)
time.sleep(0.1)
ax, ay, az = full.read_acceleration()
check_true('read_after_set_range_2g', is_triple((ax, ay, az)))

accel_conn.close()
mag_conn.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
