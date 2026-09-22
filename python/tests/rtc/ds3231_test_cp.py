"""CircuitPython hardware test for the DS3231 — runs against real hardware.

Writes and reads back the calendar clock, checks the on-chip temperature
reading is in a plausible range, and confirms the oscillator is running.
"""

import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.rtc.ds3231 import DS3231Minimal, DS3231Full

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

rtc = DS3231Minimal(connection)
check_true('construct_minimal', isinstance(rtc, DS3231Minimal))

rtc.set_datetime(2026, 9, 22, 2, 12, 0, 0)
year, month, day, weekday, hour, minute, second = rtc.get_datetime()
check_true('datetime_roundtrip_date', (year, month, day) == (2026, 9, 22))
check_true('datetime_roundtrip_time', (hour, minute) == (12, 0))

temp_c = rtc.read_temperature()
check_true('temperature_in_range', -40.0 < temp_c < 85.0)

rtc_full = DS3231Full(connection)
check_true('oscillator_running_after_set_datetime', not rtc_full.oscillator_stopped())

print('===DONE: %d passed, %d failed===' % (passed, failed))
