"""MicroPython hardware test for the MAX44009 — runs against real hardware.

Reads the illuminance and the raw exponent / mantissa, switches between
automatic and manual ranging and between the measurement modes, and checks the
threshold window, delay and interrupt registers.
"""

import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.light.max44009 import MAX44009Minimal, MAX44009Full
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

sensor = MAX44009Minimal(connection)
check_true('construct_minimal', isinstance(sensor, MAX44009Minimal))
lux = sensor.lux()
check_true('lux_in_range', 0.0 <= lux <= 188006.4)

full = MAX44009Full(connection)
exponent, mantissa, over = full.read_raw()
check_true('read_raw_ranges', 0 <= exponent <= 15 and 0 <= mantissa <= 255 and over == (exponent == 15))

full.set_continuous(True)
check_true('continuous_on', full.get_config()['continuous'] is True)
full.set_continuous(False)
check_true('continuous_off', full.get_config()['continuous'] is False)
full.set_manual(100, False)
cfg = full.get_config()
check_true('manual_100ms', cfg['manual'] and cfg['integration_ms'] == 100.0 and not cfg['divide_by_8'])
full.set_manual(25, True)
cfg = full.get_config()
check_true('manual_25ms_divide', cfg['integration_ms'] == 25.0 and cfg['divide_by_8'])
full.set_automatic()
check_true('automatic', full.get_config()['manual'] is False)

full.set_thresholds(50.0, 500.0)
low, high = full.get_thresholds()
check_true('thresholds_roundtrip', abs(low - 50.0) / 50.0 < 0.2 and abs(high - 500.0) / 500.0 < 0.2)
full.set_threshold_delay(2.0)
check_true('threshold_delay', abs(full.get_threshold_delay() - 2.0) < 0.05)
full.enable_interrupt()
full.disable_interrupt()
check_true('poll_interrupt_cleared', full.poll_interrupt() == 0)

print('===DONE: %d passed, %d failed===' % (passed, failed))
