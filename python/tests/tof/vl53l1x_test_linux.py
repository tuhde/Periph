"""Linux hardware test for the VL53L1X — runs against a Raspberry Pi.

Checks the identification registers, a single-shot distance, the measurement
record, the timing-budget / distance-mode / signal / sigma / ROI / offset /
crosstalk / threshold round trips, timed continuous ranging, the
new-sample-ready interrupt status, and the temperature update. Defaults
(long mode, 100 ms, full ROI) and the original offset are restored.
"""

import os
import time
from periph.connection.i2c_linux import I2CConnection
from periph.chips.tof.vl53l1x import (
    VL53L1XMinimal, VL53L1XFull, DISTANCE_MODE_SHORT, DISTANCE_MODE_LONG,
    SOURCE_NEW_SAMPLE_READY, I2C_ADDRESS)

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
I2C_ADDR = int(os.environ.get('I2C_ADDR', hex(I2C_ADDRESS)), 16)

connection = I2CConnection(I2C_BUS, I2C_ADDR)

sensor = VL53L1XMinimal(connection)
check_true('construct_minimal', isinstance(sensor, VL53L1XMinimal))
d = sensor.distance()
check_true('distance_in_range', 0 <= d <= 65535)
check_true('range_valid_is_bool', sensor.range_valid() in (True, False))

full = VL53L1XFull(connection)
check_true('model_id', full.model_id() == 0xEA)
check_true('module_type', full.module_type() == 0xCC)
check_true('revision_id', full.revision_id() > 0)
check_true('default_budget', full.timing_budget() == 100000)
check_true('default_mode', full.distance_mode() == DISTANCE_MODE_LONG)

full.distance()
m = full.read_measurement()
check_true('measurement_record', m['range_status'] in (0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 255)
           and m['signal_rate_mcps'] >= 0 and m['effective_spad_count'] >= 0)

full.set_timing_budget(50000)
check_true('budget_roundtrip', full.timing_budget() == 50000)
full.set_distance_mode(DISTANCE_MODE_SHORT)
check_true('mode_short', full.distance_mode() == DISTANCE_MODE_SHORT and full.timing_budget() == 50000)
check_true('short_distance', 0 <= full.distance() <= 65535)
full.set_distance_mode(DISTANCE_MODE_LONG)
full.set_timing_budget(100000)

full.set_signal_rate_limit(0.5)
check_true('signal_rate_roundtrip', full.signal_rate_limit() == 0.5)
full.set_signal_rate_limit(1.0)
full.set_sigma_threshold(60)
check_true('sigma_roundtrip', full.sigma_threshold() == 60)
full.set_sigma_threshold(90)

full.set_roi(8, 8)
check_true('roi_roundtrip', full.roi() == (8, 8))
check_true('optical_center', 0 <= full.optical_center() <= 255)
full.set_roi(16, 16)
check_true('roi_restored', full.roi() == (16, 16) and full.roi_center() == 199)

original_offset = full.offset()
full.set_offset(-10.25)
check_true('offset_roundtrip', full.offset() == -10.25)
full.set_offset(original_offset)
full.set_crosstalk_compensation(0.01)
check_true('crosstalk_roundtrip', abs(full.crosstalk_compensation() - 0.01) < 0.0001)
full.set_crosstalk_compensation(0)

full.set_interrupt_thresholds(100, 800)
check_true('thresholds_roundtrip', full.interrupt_thresholds() == (100, 800))

full.start_continuous(150)
check_true('inter_measurement', 148 <= full.inter_measurement() <= 150)
readings = [full.read_continuous() for _ in range(3)]
check_true('continuous_readings', all(0 <= r <= 65535 for r in readings))
time.sleep(0.2)
check_true('poll_interrupt_new_sample', full.poll_interrupt() == SOURCE_NEW_SAMPLE_READY)
full.stop_continuous()
time.sleep(0.2)
full.poll_interrupt()

full.recalibrate()
check_true('recalibrate_then_distance', 0 <= full.distance() <= 65535)

print('===DONE: %d passed, %d failed===' % (passed, failed))
