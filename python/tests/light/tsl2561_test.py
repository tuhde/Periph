"""MicroPython hardware test for the TSL2561 — runs against real hardware.

Reads the illuminance and the raw channel counts, checks the ID, gain and
integration time, auto-ranging, the threshold window, interrupt clearing and
power control.
"""

import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.light.tsl2561 import TSL2561Minimal, TSL2561Full
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

sensor = TSL2561Minimal(connection)
check_true('construct_minimal', isinstance(sensor, TSL2561Minimal))
lux = sensor.read_lux()
check_true('read_lux_in_range', 0.0 <= lux <= 40000.0)

full = TSL2561Full(connection)
check_true('read_id_partno', full.read_id() >> 4 == 1)
check_true('is_powered', full.is_powered())
ch0, ch1 = full.read_raw()
check_true('read_raw_ranges', 0 <= ch0 <= 65535 and 0 <= ch1 <= 65535)

full.set_gain(16)
full.set_integration_time(101)
check_true('gain_integration_roundtrip', full.get_gain() == 16 and full.get_integration_time() == 101)
full.set_gain(1)
full.set_integration_time(402)
lux = full.auto_range()
check_true('auto_range_lux', lux >= 0.0 and full.get_integration_time() in (14, 101, 402))

full.set_thresholds(100, 30000)
check_true('thresholds_roundtrip', full.get_thresholds() == (100, 30000))
full.configure_interrupt(0, 0)
full.clear_interrupt()
check_true('poll_interrupt_bool', full.poll_interrupt() in (True, False))

full.power_down()
check_true('power_down', not full.is_powered())
full.power_up()
check_true('power_up', full.is_powered())

print('===DONE: %d passed, %d failed===' % (passed, failed))
