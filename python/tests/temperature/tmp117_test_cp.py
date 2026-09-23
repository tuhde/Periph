"""CircuitPython hardware test for the TMP117 — runs against real hardware.

Checks the identity, a plausible reading, conversion-config/limit/offset
round-trips, Shutdown and one-shot conversion, the volatile EEPROM2 scratch
register, the HIGH_Alert flag, and soft reset. Never unlocks the EEPROM, so
no power-on default is changed.
"""

import time
import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.temperature.tmp117 import TMP117Minimal, TMP117Full, SOURCE_HIGH

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

sensor = TMP117Minimal(connection)
check_true('construct_minimal', isinstance(sensor, TMP117Minimal))
full = TMP117Full(connection)
full.configure(mode='continuous', averaging=8, cycle_seconds=0.125)
time.sleep(0.3)
t = sensor.read_temperature()
check_true('temperature_plausible', -40.0 <= t <= 125.0)

full.configure(mode='continuous', averaging=32, cycle_seconds=4.0)
check_true('config_roundtrip', full.get_config() == ('continuous', 32, 4.0))
full.configure(mode='shutdown', averaging=0, cycle_seconds=0.0155)
check_true('shutdown', full.is_shutdown())
full.trigger_one_shot()
time.sleep(0.05)
check_true('one_shot_data_ready', full.is_data_ready())
check_true('one_shot_returns_to_shutdown', full.is_shutdown())
full.configure()
check_true('continuous', not full.is_shutdown())

full.set_high_limit(80.0)
check_true('high_limit_roundtrip', full.get_high_limit() == 80.0)
full.set_low_limit(-10.25)
check_true('low_limit_roundtrip', full.get_low_limit() == -10.25)
full.set_temperature_offset(0.5)
check_true('offset_roundtrip', full.get_temperature_offset() == 0.5)
full.set_temperature_offset(0.0)

check_true('eeprom_not_busy', not full.is_eeprom_busy())
full.write_eeprom_scratch(2, 0xA55A)
check_true('eeprom2_volatile_roundtrip', full.read_eeprom_scratch(2) == 0xA55A)

# High limit below ambient forces HIGH_Alert on the next conversion; in
# Alert mode the flag latches until CONFIGURATION is read.
full.configure_alert(mode='alert', polarity='active_low', pin_function='alert')
full.configure(mode='continuous', averaging=0, cycle_seconds=0.0155)
full.set_high_limit(t - 20.0)
time.sleep(0.1)
check_true('poll_interrupt_high', full.poll_interrupt() & SOURCE_HIGH != 0)
full.set_high_limit(80.0)
time.sleep(0.1)
full.poll_interrupt()
check_true('poll_interrupt_clear', full.poll_interrupt() & SOURCE_HIGH == 0)

# Soft reset reloads CONFIGURATION, the limits and the offset from EEPROM.
full.reset()
check_true('reset_restores_config', full.get_config()[0] == 'continuous')

print('===DONE: %d passed, %d failed===' % (passed, failed))
