"""CircuitPython hardware test for the MCP9808 — runs against real hardware.

Checks the identity, a plausible ambient reading, resolution/boundary/
hysteresis round-trips, Shutdown and wake, the live boundary-status bits,
and the Alert output. Never sets the one-way lock bits.
"""

import time
import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.temperature.mcp9808 import MCP9808Minimal, MCP9808Full, SOURCE_LOWER

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

sensor = MCP9808Minimal(connection)
check_true('construct_minimal', isinstance(sensor, MCP9808Minimal))
t = sensor.read_temperature()
check_true('temperature_plausible', -40.0 <= t <= 125.0)

full = MCP9808Full(connection)

full.set_resolution(0.5)
check_true('resolution_0_5', full.get_resolution() == 0.5)
full.set_resolution(0.0625)
check_true('resolution_0_0625', full.get_resolution() == 0.0625)

full.set_upper_limit(80.0)
check_true('upper_limit_roundtrip', full.get_upper_limit() == 80.0)
full.set_lower_limit(-10.25)
check_true('lower_limit_roundtrip', full.get_lower_limit() == -10.25)
full.set_critical_limit(100.0)
check_true('critical_limit_roundtrip', full.get_critical_limit() == 100.0)

full.set_hysteresis(1.5)
check_true('hysteresis_roundtrip', full.get_hysteresis() == 1.5)
full.set_hysteresis(0.0)

full.shutdown()
check_true('shutdown', full.is_shutdown())
full.wake()
check_true('wake', not full.is_shutdown())

# Lower limit above ambient forces TA < TLOWER; the status bit is live
# regardless of whether the Alert output is enabled.
full.set_lower_limit(t + 20.0)
time.sleep(0.3)
check_true('poll_interrupt_lower', full.poll_interrupt() & SOURCE_LOWER != 0)
full.set_lower_limit(-10.25)
time.sleep(0.3)
check_true('poll_interrupt_clear', full.poll_interrupt() & SOURCE_LOWER == 0)

full.configure_alert(mode='all', output='comparator', polarity='active_low')
full.enable_alert()
check_true('alert_not_asserted_in_window', not full.is_alert_asserted())
full.disable_alert()
full.clear_interrupt()

# The lock bits are one-way until power-on reset and are never set here.
check_true('not_critical_locked', not full.is_critical_limit_locked())
check_true('not_window_locked', not full.is_window_limits_locked())

print('===DONE: %d passed, %d failed===' % (passed, failed))
