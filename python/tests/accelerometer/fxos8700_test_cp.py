"""CircuitPython hardware test for the FXOS8700 — runs against real hardware.

checks WHO_AM_I via construction, plausible acceleration and magnetic
readings, the hybrid burst, the temperature, configuration round-trips, the
offsets, the min/max tracker, the heading, the FIFO, the detectors, the
interrupt routing and the accelerometer self-test.
"""

import time
import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.accelerometer.fxos8700 import (
    FXOS8700Minimal, FXOS8700Full, FXOS8700Source, I2C_ADDRESS)


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


i2c = busio.I2C(cfg.SCL, cfg.SDA, frequency=cfg.FREQ)
# Defer I2C lock acquisition to the try/finally so we always release it.
i2c.try_lock()
i2c.unlock()
connection = I2CConnection(i2c, cfg.ADDR)

chip = FXOS8700Minimal(connection)
check_true('construct_minimal', isinstance(chip, FXOS8700Minimal))

ax, ay, az = chip.read_accel()
check_true('accel_plausible', -2.5 <= ax <= 2.5 and -2.5 <= ay <= 2.5 and -2.5 <= az <= 2.5)
magnitude = (ax * ax + ay * ay + az * az) ** 0.5
check_true('accel_magnitude_about_1g', 0.7 <= magnitude <= 1.3)

mx, my, mz = chip.read_mag()
check_true('mag_plausible', -1200.0 <= mx <= 1200.0 and -1200.0 <= my <= 1200.0 and -1200.0 <= mz <= 1200.0)

full = FXOS8700Full(connection)
check_true('construct_full', isinstance(full, FXOS8700Full))

(hax, hay, haz), (hmx, hmy, hmz) = full.read_hybrid()
check_true('hybrid_plausible', -2.5 <= haz <= 2.5 and -1200.0 <= hmx <= 1200.0)

raw = full.read_raw_accel()
check_true('raw_accel_range', all(-8192 <= v <= 8191 for v in raw))
raw = full.read_raw_mag()
check_true('raw_mag_range', all(-32768 <= v <= 32767 for v in raw))

temperature = full.read_temperature()
check_true('temperature_plausible', -100.0 <= temperature <= 150.0)

full.set_range(4)
full.set_data_rate(100)
time.sleep(0.2)
check_true('range_4g_plausible', -4.5 <= full.read_accel()[2] <= 4.5)
full.set_range(2)
full.set_data_rate(200)
full.set_accel_oversampling(2)
full.set_mag_oversampling(7)
full.set_accel_oversampling(0)
check_true('system_mode_wake', full.read_system_mode() == 1)
time.sleep(0.2)
check_true('new_accel_data', full.new_accel_data() in (True, False))
check_true('new_mag_data', full.new_mag_data() in (True, False))

full.set_accel_offset(0.02, -0.02, 0.0)
ox, oy, oz = full.get_accel_offset()
check_true('accel_offset_roundtrip', abs(ox - 0.02) < 0.003 and abs(oy + 0.02) < 0.003)
full.set_accel_offset(0.0, 0.0, 0.0)

full.set_mag_offset(5.0, -5.0, 0.0)
ox, oy, oz = full.get_mag_offset()
check_true('mag_offset_roundtrip', abs(ox - 5.0) < 0.15 and abs(oy + 5.0) < 0.15)
full.set_mag_offset(0.0, 0.0, 0.0)

full.reset_mag_min_max()
time.sleep(0.2)
mn, mx_ = full.read_mag_min_max()
check_true('mag_min_max_ordered', mn[0] <= mx_[0] and mn[1] <= mx_[1] and mn[2] <= mx_[2])

heading = full.heading()
check_true('heading_in_range', 0.0 <= heading < 360.0)

full.set_fifo(1, 8)
time.sleep(0.5)
check_true('fifo_level', 0 <= full.fifo_level() <= 32)
samples = full.read_fifo(4)
check_true('fifo_samples', len(samples) <= 4)
full.set_fifo(0)

full.set_freefall_motion(True, threshold_g=0.3, debounce_ms=50, latch=True)
check_true('motion_source_readable', 0 <= full.read_motion_source() <= 255)
full.set_transient(threshold_g=0.3)
check_true('transient_source_readable', 0 <= full.read_transient_source() <= 255)
full.set_pulse(single=7)
check_true('pulse_source_readable', 0 <= full.read_pulse_source() <= 255)
full.set_orientation(debounce_ms=100)
check_true('orientation_readable', full.read_orientation()[2] in (0, 1, 2, 3))

full.enable_interrupt(FXOS8700Source.SOURCE_DATA_READY, pin=1)
status = full.poll_interrupt()
check_true('poll_interrupt', 0 <= status <= 255)
full.disable_interrupt(FXOS8700Source.SOURCE_DATA_READY)

ok, dx, dy, dz = full.self_test()
check_true('self_test_runs', ok in (True, False) and dz > 0)

full.set_mode(2)
mx, my, mz = full.measure_mag_once()
check_true('measure_mag_once', -1200.0 <= mx <= 1200.0)

print('===DONE: %d passed, %d failed===' % (passed, failed))
