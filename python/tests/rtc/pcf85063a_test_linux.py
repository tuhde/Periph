"""Linux hardware test for the PCF85063A — runs against a Raspberry Pi.

Writes and reads back the calendar clock, confirms the oscillator-stop flag
is cleared by set_datetime, and round-trips the alarm, offset, RAM byte and countdown timer.
"""

import os
from periph.connection.i2c_linux import I2CConnection
from periph.chips.rtc.pcf85063a import PCF85063AMinimal, PCF85063AFull, I2C_ADDRESS

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

rtc = PCF85063AMinimal(connection)
check_true('construct_minimal', isinstance(rtc, PCF85063AMinimal))

rtc.set_datetime(2026, 9, 23, 3, 12, 0, 0)
year, month, day, weekday, hour, minute, second = rtc.get_datetime()
check_true('datetime_roundtrip_date', (year, month, day, weekday) == (2026, 9, 23, 3))
check_true('datetime_roundtrip_time', (hour, minute) == (12, 0))

rtc_full = PCF85063AFull(connection)
check_true('oscillator_running_after_set_datetime', not rtc_full.oscillator_stopped())

rtc_full.set_alarm(second=0, minute=15, hour=6)
check_true('alarm_roundtrip', rtc_full.get_alarm() == (0, 15, 6, None, None))
rtc_full.set_alarm()

rtc_full.set_offset(-3, 'course')
check_true('offset_roundtrip', rtc_full.get_offset() == (-3, 'course'))
rtc_full.set_offset(0)

rtc_full.write_ram(0xA5)
check_true('ram_roundtrip', rtc_full.read_ram() == 0xA5)

rtc_full.configure_timer(30, '1hz')
check_true('timer_running', 0 < rtc_full.read_timer() <= 30)
rtc_full.disable_timer()

print('===DONE: %d passed, %d failed===' % (passed, failed))
