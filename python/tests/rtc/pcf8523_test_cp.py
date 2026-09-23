"""CircuitPython hardware test for the PCF8523 — runs against real hardware.

Writes and reads back the calendar clock, confirms the oscillator-stop flag
is cleared by set_datetime, and round-trips the alarm and offset registers.
"""

import busio
import _testconfig as cfg
from periph.connection.i2c_circuitpython import I2CConnection
from periph.chips.rtc.pcf8523 import PCF8523Minimal, PCF8523Full

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

rtc = PCF8523Minimal(connection)
check_true('construct_minimal', isinstance(rtc, PCF8523Minimal))

rtc.set_datetime(2026, 9, 23, 3, 12, 0, 0)
year, month, day, weekday, hour, minute, second = rtc.get_datetime()
check_true('datetime_roundtrip_date', (year, month, day, weekday) == (2026, 9, 23, 3))
check_true('datetime_roundtrip_time', (hour, minute) == (12, 0))

rtc_full = PCF8523Full(connection)
check_true('oscillator_running_after_set_datetime', not rtc_full.oscillator_stopped())

rtc_full.set_alarm(minute=15, hour=6)
check_true('alarm_roundtrip', rtc_full.get_alarm() == (15, 6, None, None))
rtc_full.set_alarm()

rtc_full.set_offset(-3, 'every_minute')
check_true('offset_roundtrip', rtc_full.get_offset() == (-3, 'every_minute'))
rtc_full.set_offset(0)

print('===DONE: %d passed, %d failed===' % (passed, failed))
