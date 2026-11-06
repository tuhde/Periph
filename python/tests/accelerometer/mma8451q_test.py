"""MicroPython hardware test for the MMA8451Q — runs against real hardware.

Verifies WHO_AM_I, exercises the range / data-rate configuration, performs
a 6-byte burst read of the X/Y/Z data, checks the orientation and FIFO
paths, and verifies that the interrupt sources can be enabled and polled.
"""

import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.mma8451q import MMA8451QMinimal, MMA8451QFull
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

accel = MMA8451QMinimal(connection)
check_true('construct_minimal', isinstance(accel, MMA8451QMinimal))

x, y, z = accel.read()
check_true('read_returns_three_floats',
           isinstance(x, float) and isinstance(y, float) and isinstance(z, float))
check_true('read_magnitude_near_1g',
           abs((x * x + y * y + z * z) ** 0.5 - 1.0) < 0.5)

# Full driver: range switch + read still works.
accel_full = MMA8451QFull(connection)
check_true('construct_full', isinstance(accel_full, MMA8451QFull))
accel_full.set_range(4)
x, y, z = accel_full.read()
check_true('read_after_set_range_4g',
           isinstance(x, float) and isinstance(y, float) and isinstance(z, float))
check_true('read_magnitude_near_1g_at_4g',
           abs((x * x + y * y + z * z) ** 0.5 - 1.0) < 0.5)

# Raw counts and the status/system-mode readback.
rx, ry, rz = accel_full.read_raw()
check_true('read_raw_returns_ints',
           isinstance(rx, int) and isinstance(ry, int) and isinstance(rz, int))
status = accel_full.read_status()
check_true('read_status_in_range', 0 <= status <= 0xFF)
mode = accel_full.read_system_mode()
check_true('read_system_mode_active', mode == 1)

# Data rate and oversampling.
accel_full.set_data_rate(50)
accel_full.set_oversampling(2)
x, y, z = accel_full.read()
check_true('read_after_set_data_rate', isinstance(x, float))

# Offsets: write and read back.
accel_full.set_offset(0.01, -0.01, 0.0)
ox, oy, oz = accel_full.get_offset()
check_true('offset_roundtrip',
           abs(ox - 0.01) < 0.005 and abs(oy - (-0.01)) < 0.005)

# FIFO in circular mode.
accel_full.set_fifo(1, 16)
accel_full.set_data_rate(100)
import time
time.sleep_ms(250)
level = accel_full.fifo_level()
check_true('fifo_level_within_range', 0 <= level <= 32)
overflow, watermark, count = accel_full.read_fifo_status()
check_true('read_fifo_status_types',
           isinstance(overflow, bool) and isinstance(watermark, bool))
samples = accel_full.read_fifo(4)
check_true('read_fifo_returns_samples', len(samples) <= 4)
accel_full.set_fifo(0)

# Orientation detector.
accel_full.set_orientation(True, debounce_ms=100)
changed, lockout, lapo, back = accel_full.read_orientation()
check_true('read_orientation_tuple',
           isinstance(changed, bool) and isinstance(lockout, bool)
           and lapo in (0, 1, 2, 3) and isinstance(back, bool))

# Interrupt enable / disable and polling.
accel_full.enable_interrupt(accel_full.SOURCE_DATA_READY, pin=1)
status = accel_full.poll_interrupt()
check_true('poll_interrupt_returns_int', isinstance(status, int))
accel_full.disable_interrupt(accel_full.SOURCE_DATA_READY)

connection.close()
print('===DONE: {} passed, {} failed==='.format(passed, failed))
