"""MicroPython hardware test for the LIS3DH — runs against real hardware.

Verifies the WHO_AM_I identity check inside init, exercises the default
configuration (±2 g, 100 Hz, high-resolution) and checks the burst read
returns a plausible gravity vector.
"""

import machine
import time
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.accelerometer.lis3dh import LIS3DHMinimal, LIS3DHFull, LIS3DHSource
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

accel = LIS3DHMinimal(connection)
check_true('construct_minimal', isinstance(accel, LIS3DHMinimal))

x, y, z = accel.read()
check_true('read_returns_three_floats',
           isinstance(x, float) and isinstance(y, float) and isinstance(z, float))
check_true('read_magnitude_near_1g',
           abs((x * x + y * y + z * z) ** 0.5 - 1.0) < 0.5)
check_true('read_z_nonzero_gravity', abs(z) > 0.5)


# --- Full driver ---------------------------------------------------------

accel_full = LIS3DHFull(connection)
check_true('construct_full', isinstance(accel_full, LIS3DHFull))

# Range / mode / data-rate selection.
accel_full.set_range(4)
accel_full.set_mode(1)
accel_full.set_data_rate(100)
accel_full.set_axes(True, True, True)
x, y, z = accel_full.read()
check_true('read_after_config',
           isinstance(x, float) and isinstance(y, float) and isinstance(z, float))
rx, ry, rz = accel_full.read_raw()
check_true('read_raw_ints', isinstance(rx, int) and isinstance(ry, int) and isinstance(rz, int))
check_true('read_status_range', 0 <= accel_full.read_status() <= 255)
check_true('new_data_available_bool', isinstance(accel_full.new_data_available(), bool))

# Back to the defaults.
accel_full.set_mode(2)
accel_full.set_range(2)

# Auxiliary ADC and temperature.
accel_full.enable_adc(True)
v1 = accel_full.read_adc(1)
v2 = accel_full.read_adc(2)
check_true('read_adc_voltages', 0.7 < v1 < 1.7 and 0.7 < v2 < 1.7)
accel_full.enable_adc(False)
temp = accel_full.read_temperature_delta()
check_true('read_temperature_delta', isinstance(temp, int) and -128 <= temp <= 127)

# High-pass filter.
accel_full.set_high_pass(mode=1, cutoff=2, filter_output=True,
                          on_ia1=True, on_ia2=False, on_click=True)
ref = accel_full.reset_high_pass()
check_true('reset_high_pass', isinstance(ref, int) and 0 <= ref <= 255)
accel_full.set_reference(0x40)
accel_full.set_high_pass(0, 0)

# FIFO.
accel_full.set_fifo(1, watermark=8)
level = accel_full.fifo_level()
check_true('fifo_level_range', 0 <= level <= 32)
watermark, overrun, empty, lvl = accel_full.read_fifo_status()
check_true('read_fifo_status', isinstance(watermark, bool) and isinstance(empty, bool))
samples = accel_full.read_fifo()
check_true('read_fifo_list', isinstance(samples, list) and len(samples) <= 32)
accel_full.set_fifo(0)

# Interrupt generators, click and activity.
accel_full.set_motion_interrupt(1, 0x3F, 0.5, 30, True)
check_true('read_interrupt_source', 0 <= accel_full.read_interrupt_source(1) <= 255)
accel_full.set_free_fall(generator=1, threshold_g=0.35, duration_ms=30)
accel_full.set_orientation(generator=1, position=False, four_d=False)
accel_full.set_latch(1, True)
accel_full.set_latch(1, False)
accel_full.set_click(single=0x01, double=0x04, threshold_g=0.5,
                     time_limit_ms=10, latency_ms=20, window_ms=50, latch=False)
check_true('read_click_source', 0 <= accel_full.read_click_source() <= 255)
accel_full.set_activity(0.5, 100)
accel_full.set_interrupt_polarity(False)

# Interrupt routing and polling.
accel_full.enable_interrupt(LIS3DHSource.SOURCE_DATA_READY, 1)
status = accel_full.poll_interrupt()
check_true('poll_interrupt_int', isinstance(status, int))
accel_full.disable_interrupt(LIS3DHSource.SOURCE_DATA_READY, 1)

# Reboot and SDO pull-up.
accel_full.set_sdo_pullup(True)
accel_full.reboot()
x, y, z = accel_full.read()
check_true('read_after_reboot', isinstance(x, float) and isinstance(y, float))

print('===DONE: {} passed, {} failed==='.format(passed, failed))
