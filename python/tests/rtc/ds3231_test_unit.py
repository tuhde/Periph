"""Unit test for the DS3231 — runs without hardware using the I2C mock.

Verifies BCD conversion, 24-hour enforcement, alarm match-mode encoding,
and interrupt status masking by observing what the driver writes to the
mock connection.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.rtc.ds3231 import (
    DS3231Minimal, DS3231Full,
    ALARM1_MATCH_HOURS_MINUTES_SECONDS, ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS,
    ALARM2_EVERY_MINUTE,
    SOURCE_ALARM1, SOURCE_ALARM2,
)

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


# get_datetime: 2026-09-22 Tuesday 14:30:45, 24-hour mode.
mock = I2CConnectionMock()
mock.set_register(DS3231Minimal._REG_SECONDS, 0x45, 0x30, 0x14, 0x02, 0x22, 0x09, 0x26)
rtc = DS3231Minimal(mock)
dt = rtc.get_datetime()
check_true('get_datetime_time', dt[4:7] == (14, 30, 45))
check_true('get_datetime_date', dt[0:4] == (2026, 9, 22, 2))

# get_datetime: 12-hour mode, 11 PM -> 23:00.
mock2 = I2CConnectionMock()
mock2.set_register(DS3231Minimal._REG_SECONDS, 0x00, 0x00, 0x71, 0x01, 0x01, 0x01, 0x25)
rtc2 = DS3231Minimal(mock2)
check_true('get_datetime_12h_pm_decode', rtc2.get_datetime()[4] == 23)

# set_datetime: forces 24-hour mode and clears OSF.
mock3 = I2CConnectionMock()
mock3.set_register(DS3231Minimal._REG_STATUS, 0x80)  # OSF set
rtc3 = DS3231Minimal(mock3)
rtc3.set_datetime(2026, 9, 22, 2, 14, 30, 45)
hour_ok = any(len(w) >= 4 and w[0] == DS3231Minimal._REG_SECONDS and w[3] == 0x14 for w in mock3.writes)
osf_cleared = any(len(w) == 2 and w[0] == DS3231Minimal._REG_STATUS and w[1] == 0x00 for w in mock3.writes)
check_true('set_datetime_writes_24h_bcd_hour', hour_ok)
check_true('set_datetime_clears_osf', osf_cleared)

# read_temperature: +25.25 C (MSB=0x19, LSB=0x40).
mock4 = I2CConnectionMock()
mock4.set_register(DS3231Minimal._REG_TEMP_MSB, 0x19, 0x40)
rtc4 = DS3231Minimal(mock4)
check_true('read_temperature_positive', abs(rtc4.read_temperature() - 25.25) < 1e-9)

# read_temperature: negative, -9.75 C (MSB=0xF6 = -10, frac=0.25).
mock5 = I2CConnectionMock()
mock5.set_register(DS3231Minimal._REG_TEMP_MSB, 0xF6, 0x40)
rtc5 = DS3231Minimal(mock5)
check_true('read_temperature_negative', abs(rtc5.read_temperature() - (-9.75)) < 1e-9)

# Alarm1 match-mode round trip: MATCH_HOURS_MINUTES_SECONDS.
mock6 = I2CConnectionMock()
rtc6 = DS3231Full(mock6)
rtc6.set_alarm1(15, 30, 9, 0, False, ALARM1_MATCH_HOURS_MINUTES_SECONDS)
a1 = rtc6.get_alarm1()
check_true('alarm1_match_mode_roundtrip', a1['match_mode'] == ALARM1_MATCH_HOURS_MINUTES_SECONDS)
check_true('alarm1_fields_roundtrip', (a1['second'], a1['minute'], a1['hour']) == (15, 30, 9))

# Alarm1 match-mode round trip: MATCH_DAY (day-of-week).
mock7 = I2CConnectionMock()
rtc7 = DS3231Full(mock7)
rtc7.set_alarm1(0, 0, 0, 3, True, ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS)
a1b = rtc7.get_alarm1()
check_true('alarm1_day_mode_roundtrip', a1b['match_mode'] == ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS)
check_true('alarm1_day_fields_roundtrip', a1b['is_day_of_week'] and a1b['day_or_date'] == 3)

# Alarm2 match-mode round trip: EVERY_MINUTE.
mock8 = I2CConnectionMock()
rtc8 = DS3231Full(mock8)
rtc8.set_alarm2(0, 0, 0, False, ALARM2_EVERY_MINUTE)
check_true('alarm2_every_minute_roundtrip', rtc8.get_alarm2()['match_mode'] == ALARM2_EVERY_MINUTE)

# Interrupt status masking: poll_interrupt clears A1F/A2F, leaves OSF/EN32kHz.
mock9 = I2CConnectionMock()
mock9.set_register(DS3231Minimal._REG_STATUS, 0x80 | 0x08 | 0x01 | 0x02)
rtc9 = DS3231Full(mock9)
status = rtc9.poll_interrupt()
check_true('poll_interrupt_reports_alarm1', bool(status & SOURCE_ALARM1))
check_true('poll_interrupt_reports_alarm2', bool(status & SOURCE_ALARM2))
after = [w[1] for w in mock9.writes if len(w) == 2 and w[0] == DS3231Minimal._REG_STATUS][-1]
check_true('poll_interrupt_clears_alarm_flags', (after & 0x01) == 0 and (after & 0x02) == 0)
check_true('poll_interrupt_preserves_osf_en32khz', (after & 0x80) != 0 and (after & 0x08) != 0)

# enableInterrupt sets INTCN + A1IE/A2IE.
mock10 = I2CConnectionMock()
rtc10 = DS3231Full(mock10)
rtc10.enable_interrupt(SOURCE_ALARM1 | SOURCE_ALARM2)
ctrl = [w[1] for w in mock10.writes if len(w) == 2 and w[0] == DS3231Minimal._REG_CONTROL][-1]
check_true('enable_interrupt_sets_intcn_and_both_ie_bits', (ctrl & 0x04) and (ctrl & 0x01) and (ctrl & 0x02))

# Aging offset: signed round trip.
mock11 = I2CConnectionMock()
mock11.set_register(DS3231Minimal._REG_AGING_OFFSET, 0xFB)  # -5
rtc11 = DS3231Full(mock11)
check_true('aging_offset_signed_read', rtc11.get_aging_offset() == -5)
rtc11.set_aging_offset(-100)
raw = [w[1] for w in mock11.writes if len(w) == 2 and w[0] == DS3231Minimal._REG_AGING_OFFSET][-1]
check_true('aging_offset_signed_write', raw == 0x9C)  # -100 & 0xFF

print('===DONE: %d passed, %d failed===' % (passed, failed))
if failed:
    raise SystemExit(1)
