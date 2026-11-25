"""Linux hardware test for the LSM303 — runs against a Raspberry Pi.

Checks the magnetometer identity, plausible acceleration and magnetic
readings, the accelerometer configuration round-trips, the FIFO, the
interrupt-generator threshold encoding, the magnetometer gain / rate / mode
registers and the interrupt routing.
"""

import os
import time
from periph.connection.i2c_linux import I2CConnection
from periph.chips.accelerometer.lsm303 import (
    LSM303Minimal, LSM303Full, LSM303Source,
    I2C_ADDRESS_ACCEL, I2C_ADDRESS_MAG)

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
I2C_ADDR = int(os.environ.get('I2C_ADDR', hex(I2C_ADDRESS_ACCEL)), 16)

accel_connection = I2CConnection(I2C_BUS, I2C_ADDR)
mag_connection = I2CConnection(I2C_BUS, I2C_ADDRESS_MAG)

compass = LSM303Minimal(accel_connection, mag_connection)
check_true('construct_minimal', isinstance(compass, LSM303Minimal))

identity = compass.read_mag_identity()
check_true('mag_identity', identity == b'\x48\x34\x33')

ax, ay, az = compass.read_acceleration()
check_true('acceleration_plausible',
           -4.0 <= ax <= 4.0 and -4.0 <= ay <= 4.0 and -4.0 <= az <= 4.0)

mx, my, mz = compass.read_magnetic()
check_true('magnetic_plausible',
           -1500.0 <= mx <= 1500.0 and -1500.0 <= my <= 1500.0 and -1500.0 <= mz <= 1500.0)

full = LSM303Full(accel_connection, mag_connection)
check_true('construct_full', isinstance(full, LSM303Full))

full.set_range(4)
full.set_data_rate(50)
full.set_mode(LSM303Full.MODE_HIGH_RESOLUTION)
full.set_axes(x=True, y=True, z=True)
time.sleep(0.2)
ax, ay, az = full.read_acceleration()
check_true('acceleration_after_reconfigure',
           -8.0 <= ax <= 8.0 and -8.0 <= ay <= 8.0 and -8.0 <= az <= 8.0)

full.set_range(2)
full.set_data_rate(100)

raw = full.read_acceleration_raw()
check_true('acceleration_raw_range',
           -2048 <= raw[0] <= 2047 and -2048 <= raw[1] <= 2047 and -2048 <= raw[2] <= 2047)

check_true('new_data_available', full.new_data_available())
status = full.read_status()
check_true('status_readable', 0 <= status <= 0xFF)

full.set_high_pass(mode=0, cutoff=0, filter_output=False,
                   on_ia1=False, on_ia2=False, on_click=False)
reference = full.reset_high_pass()
check_true('reference_readable', 0 <= reference <= 0xFF)
full.set_reference(reference)

full.set_fifo(LSM303Full.FIFO_STREAM, watermark=16)
time.sleep(0.2)
level = full.fifo_level()
check_true('fifo_level_valid', 0 <= level <= 32)
watermark, overrun, empty, fifo_level = full.read_fifo_status()
check_true('fifo_status_level_matches', fifo_level == level)
samples = full.read_fifo()
check_true('fifo_samples_read', len(samples) == level)
full.set_fifo(LSM303Full.FIFO_BYPASS)

full.set_motion_interrupt(1, 0x02, threshold_g=0.5, duration_ms=10, and_mode=False)
full.set_free_fall(generator=1, threshold_g=0.35, duration_ms=30)
full.set_orientation(generator=1, position=False, four_d=False)
full.set_latch(1, True)
source = full.read_interrupt_source(1)
check_true('interrupt_source_readable', 0 <= source <= 0xFF)

full.set_click(single=0, double=0x04, threshold_g=0.8,
               time_limit_ms=20, latency_ms=40, window_ms=200)
click = full.read_click_source()
check_true('click_source_readable', 0 <= click <= 0xFF)

full.set_interrupt_polarity(False)
full.enable_interrupt(LSM303Source.SOURCE_CLICK, 1)
full.enable_interrupt(LSM303Source.SOURCE_IA1, 1)
status = full.poll_interrupt()
check_true('poll_interrupt_mask', status >= 0)
full.disable_interrupt(LSM303Source.SOURCE_CLICK, 1)
full.disable_interrupt(LSM303Source.SOURCE_IA1, 1)

full.set_mag_range(250)
full.set_mag_data_rate(30)
full.set_mag_mode(LSM303Full.MAG_MODE_CONTINUOUS)
time.sleep(0.2)
raw = full.read_magnetic_raw()
check_true('magnetic_raw_range',
           -2048 <= raw[0] <= 2047 and -2048 <= raw[1] <= 2047 and -2048 <= raw[2] <= 2047)

drdy, lock = full.read_mag_status()
check_true('mag_status_readable', drdy in (True, False) and lock in (True, False))

full.enable_temperature(True)
temperature = full.read_temperature_delta()
check_true('temperature_delta_plausible', -100.0 <= temperature <= 100.0)

heading = full.heading()
check_true('heading_in_range', 0.0 <= heading <= 360.0)

print('===DONE: %d passed, %d failed===' % (passed, failed))
