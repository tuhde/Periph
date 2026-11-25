"""MicroPython hardware test for the TMP102 — runs against real hardware.

Checks temperature reads, conversion rate, limits, fault queue, polarity,
thermostat mode, extended mode, shutdown/one-shot/wake and the alert state.
No power-on default is left changed.
"""

import time
import machine
import _testconfig as cfg
from periph.connection.i2c_micropython import I2CConnection
from periph.chips.temperature.tmp102 import TMP102Minimal, TMP102Full
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

sensor = TMP102Minimal(connection)
check_true('construct_minimal', isinstance(sensor, TMP102Minimal))
full = TMP102Full(connection)
time.sleep(0.05)
t = sensor.read_temperature()
check_true('temperature_plausible', -40.0 <= t <= 125.0)
check_true('raw_matches_temperature', abs(full.read_raw() * 0.0625 - t) < 1.0)
check_true('default_config', full.read_config() & 0x6000 == 0x6000)

full.set_conversion_rate(1)
check_true('rate_roundtrip', (full.read_config() >> 6) & 3 == 1)
full.set_conversion_rate(4)

full.set_t_high(40.0)
check_true('t_high_roundtrip', full.get_t_high() == 40.0)
full.set_t_low(-10.25)
check_true('t_low_roundtrip', full.get_t_low() == -10.25)
full.set_t_high(80.0)
full.set_t_low(75.0)

full.set_fault_queue(4)
check_true('fault_queue', (full.read_config() >> 11) & 3 == 2)
full.set_fault_queue(1)
full.set_polarity(True)
check_true('polarity', bool(full.read_config() & 0x0400))
full.set_polarity(False)
full.set_thermostat_mode(True)
check_true('thermostat_mode', bool(full.read_config() & 0x0200))
full.set_thermostat_mode(False)

full.set_extended_mode(True)
time.sleep(0.3)
check_true('extended_mode_reading', abs(full.read_temperature() - t) < 3.0)
full.set_extended_mode(False)

full.shutdown()
check_true('shutdown', bool(full.read_config() & 0x0100))
start = time.time()
t1 = full.one_shot()
check_true('one_shot_plausible', -40.0 <= t1 <= 125.0)
check_true('one_shot_fast', time.time() - start < 0.1)
check_true('one_shot_stays_shutdown', bool(full.read_config() & 0x0100))
full.wake()
check_true('wake', not (full.read_config() & 0x0100))

# T_HIGH below ambient forces an alert after the fault queue elapses.
full.set_thermostat_mode(False)
full.set_t_low(t - 21.0)
full.set_t_high(t - 20.0)
time.sleep(0.5)
check_true('alert_state_active', full.alert_state())
check_true('poll_interrupt_active', full.poll_interrupt())
full.set_t_high(80.0)
full.set_t_low(75.0)
time.sleep(0.5)
check_true('alert_state_clear', not full.alert_state())

print('===DONE: %d passed, %d failed===' % (passed, failed))
