"""MicroPython hardware test for the VL53L0X — runs against real hardware.

Checks the model ID, a single-shot distance, the measurement record, the
timing-budget / signal-rate / VCSEL / offset / threshold round trips, a
profile, back-to-back and timed continuous ranging, the new-sample-ready
interrupt status, and reference recalibration. The ranging profile is left
at default and the original offset restored.
"""

import time
import machine
import _testconfig as cfg
from machine import Pin
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.tof.vl53l0x import (
    VL53L0XMinimal, VL53L0XFull, PRE_RANGE, FINAL_RANGE, SOURCE_NEW_SAMPLE_READY, I2C_ADDRESS)

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

sensor = VL53L0XMinimal(connection)
check_true('construct_minimal', isinstance(sensor, VL53L0XMinimal))
d = sensor.distance()
check_true('distance_in_range', 0 <= d <= 8191)
check_true('range_valid_is_bool', sensor.range_valid() in (True, False))

full = VL53L0XFull(connection)
check_true('model_id', full.model_id() == 0xEE)
check_true('revision_id', full.revision_id() > 0)
check_true('default_budget', 20000 <= full.timing_budget() <= 40000)

full.distance()
m = full.read_measurement()
check_true('measurement_record', 0 <= m['range_status'] <= 15 and m['signal_rate_mcps'] >= 0)

full.set_timing_budget(50000)
check_true('budget_roundtrip', abs(full.timing_budget() - 50000) < 300)
full.set_signal_rate_limit(0.1)
check_true('signal_rate_roundtrip', abs(full.signal_rate_limit() - 0.1) < 0.01)
full.set_vcsel_pulse_period(PRE_RANGE, 18)
full.set_vcsel_pulse_period(FINAL_RANGE, 14)
check_true('vcsel_roundtrip', full.vcsel_pulse_period(PRE_RANGE) == 18
           and full.vcsel_pulse_period(FINAL_RANGE) == 14)
full.set_profile('default')
check_true('profile_default', full.vcsel_pulse_period(PRE_RANGE) == 14
           and abs(full.timing_budget() - 33000) < 300)

original_offset = full.offset()
full.set_offset(-10.25)
check_true('offset_roundtrip', full.offset() == -10.25)
full.set_offset(original_offset)

full.set_interrupt_thresholds(100, 800)
check_true('thresholds_roundtrip', full.interrupt_thresholds() == (100, 800))

# Back-to-back continuous ranging, then timed mode.
full.start_continuous()
readings = [full.read_continuous() for _ in range(3)]
check_true('continuous_readings', all(0 <= r <= 8191 for r in readings))
full.stop_continuous()
time.sleep(0.05)
full.poll_interrupt()
full.start_continuous(100)
readings = [full.read_continuous() for _ in range(2)]
check_true('timed_readings', all(0 <= r <= 8191 for r in readings))
full.stop_continuous()
time.sleep(0.15)
full.poll_interrupt()

full.distance()
full.start_continuous()
time.sleep(0.1)
check_true('poll_interrupt_new_sample', full.poll_interrupt() == SOURCE_NEW_SAMPLE_READY)
full.stop_continuous()
time.sleep(0.05)
full.poll_interrupt()

full.recalibrate()
check_true('recalibrate_then_distance', 0 <= full.distance() <= 8191)

print('===DONE: %d passed, %d failed===' % (passed, failed))
