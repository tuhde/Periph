"""MicroPython hardware test for the APDS-9250 — runs against real hardware.

Reads all four channels, checks the identity and defaults, steps gain, resolution
and rate, switches the mode, round-trips the thresholds and exercises the
interrupt configuration and the software reset.
"""

import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.light.apds9250 import APDS9250Minimal, APDS9250Full, SOURCE_VARIATION, STATUS_INTERRUPT
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

sensor = APDS9250Minimal(connection)
check_true('construct_minimal', isinstance(sensor, APDS9250Minimal))
counts = sensor.read()
check_true('read_keys', sorted(counts.keys()) == ['blue', 'green', 'ir', 'red'])
check_true('read_range', all(0 <= v <= 0xFFFFF for v in counts.values()))

full = APDS9250Full(connection)
check_true('part_id', full.part_id()[0] == 0xB)
check_true('defaults', full.get_resolution() == 18 and full.get_measurement_rate() == 100 and full.get_gain() == 3)

full.set_gain(18)
check_true('gain_18', full.get_gain() == 18)
full.set_gain(3)
full.set_resolution(16)
check_true('resolution_16', full.get_resolution() == 16 and abs(full.integration_time() - 0.025) < 1e-9)
full.set_resolution(18)
full.set_measurement_rate(200)
check_true('rate_200', full.get_measurement_rate() == 200)
full.set_measurement_rate(100)

red, green, blue, ir = full.read_channels()
check_true('read_channels_range', all(0 <= v <= 0xFFFFF for v in (red, green, blue, ir)))
check_true('read_single_green', 0 <= full.read_green() <= 0xFFFFF)

full.set_mode(False)
check_true('als_mode_reads', 0 <= full.read_ir() <= 0xFFFFF)
full.set_mode(True)

full.set_thresholds(100, 200000)
check_true('thresholds_roundtrip', full.get_thresholds() == (100, 200000))
full.set_persistence(3)
full.set_variance(64)
full.set_interrupt_channel('green')
full.enable_interrupt(SOURCE_VARIATION)
full.disable_interrupt()
full.status()
check_true('status_clean', full.poll_interrupt() & STATUS_INTERRUPT == 0)

full.disable()
full.enable()
full.soft_reset()
check_true('soft_reset_defaults', full.get_gain() == 3 and full.get_resolution() == 18)

print('===DONE: %d passed, %d failed===' % (passed, failed))
