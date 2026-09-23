"""Linux hardware test for the TMP117 — runs against a Raspberry Pi.

Checks the identity, a plausible reading, conversion-config/limit/offset
round-trips, Shutdown and one-shot conversion, the volatile EEPROM2 scratch
register, one EEPROM program cycle (EEPROM2 rewritten with its own value),
the HIGH_Alert flag, and soft reset. No power-on default is changed.
"""

import os
import time
from periph.connection.i2c_linux import I2CConnection
from periph.chips.temperature.tmp117 import TMP117Minimal, TMP117Full, SOURCE_HIGH, I2C_ADDRESS

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
eeprom2 = full.read_eeprom_scratch(2)
full.write_eeprom_scratch(2, 0xA55A)
check_true('eeprom2_volatile_roundtrip', full.read_eeprom_scratch(2) == 0xA55A)

# One real EEPROM program cycle (the conformance-checked eeprom_write_ready
# timing): rewrite EEPROM2's original value while unlocked, so the stored
# power-on value is unchanged. Costs one EEPROM2 endurance cycle per run.
full.unlock_eeprom()
full.write_eeprom_scratch(2, eeprom2)
waited_ms = 0
while full.is_eeprom_busy() and waited_ms < 50:
    time.sleep(0.001)
    waited_ms += 1
full.lock_eeprom()
check_true('eeprom_write_ready', waited_ms < 50)
check_true('eeprom2_restored', full.read_eeprom_scratch(2) == eeprom2)

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
