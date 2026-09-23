"""Unit test for the PCF8523 — runs without hardware using the I2C mock.

Verifies BCD conversion, the STOP-bit set_datetime sequence, the init-time
battery switch-over default, active-low alarm enables, the offset
two's-complement encoding, timer configuration, and interrupt status
mapping/flag clearing by observing what the driver writes to the mock.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.rtc.pcf8523 import (
    PCF8523Minimal, PCF8523Full,
    SOURCE_SECOND, SOURCE_TIMER_A, SOURCE_TIMER_B, SOURCE_ALARM,
    SOURCE_BATTERY_SWITCH, SOURCE_BATTERY_LOW,
)

passed = 0
failed = 0

R = PCF8523Minimal


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


def last_write(mock, reg):
    return [w[1] for w in mock.writes if len(w) == 2 and w[0] == reg][-1]


# init: writes CONTROL_3 = 0x00 (PM=000, battery switch-over standard mode).
mock = I2CConnectionMock()
mock.set_register(R._REG_CONTROL_3, 0xE0)
PCF8523Minimal(mock)
check_true('init_sets_pm_standard', last_write(mock, R._REG_CONTROL_3) == 0x00)

# get_datetime: 2026-09-23 Wednesday(3) 14:30:45, OS flag set is masked off.
mock = I2CConnectionMock()
mock.set_register(R._REG_SECONDS, 0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26)
rtc = PCF8523Minimal(mock)
dt = rtc.get_datetime()
check_true('get_datetime_time', dt[4:7] == (14, 30, 45))
check_true('get_datetime_date', dt[0:4] == (2026, 9, 23, 3))

# set_datetime: STOP=1, 7-byte BCD write with OS=0, STOP=0; 12_24 cleared.
mock = I2CConnectionMock()
mock.set_register(R._REG_CONTROL_1, 0x08)  # 12-hour mode set
rtc = PCF8523Minimal(mock)
mock.writes.clear()
rtc.set_datetime(2026, 9, 23, 3, 14, 30, 45)
ctrl_writes = [w[1] for w in mock.writes if len(w) == 2 and w[0] == R._REG_CONTROL_1]
time_write = [w for w in mock.writes if len(w) == 8 and w[0] == R._REG_SECONDS]
check_true('set_datetime_stop_sequence', ctrl_writes == [0x20, 0x00])
check_true('set_datetime_bcd_payload',
           len(time_write) == 1 and time_write[0][1:] == bytes([0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26]))

# Alarm: None fields disabled (bit 7 set), values BCD with AEN=0; round trip.
mock = I2CConnectionMock()
rtc = PCF8523Full(mock)
rtc.set_alarm(minute=45, weekday=6)
check_true('set_alarm_registers',
           [mock.registers[r] for r in range(0x0A, 0x0E)] == [0x45, 0x80, 0x80, 0x06])
check_true('get_alarm_roundtrip', rtc.get_alarm() == (45, None, None, 6))

# Offset: signed 7-bit round trip, mode bit.
mock = I2CConnectionMock()
rtc = PCF8523Full(mock)
rtc.set_offset(-64, 'every_minute')
check_true('set_offset_encoding', mock.registers[R._REG_OFFSET] == 0xC0)
check_true('get_offset_roundtrip', rtc.get_offset() == (-64, 'every_minute'))
rtc.set_offset(63)
check_true('get_offset_positive', rtc.get_offset() == (63, 'every_two_hours'))

# Timer A watchdog: TAQ, T_A, TAC=10, TAM; enable_interrupt picks WTAIE.
mock = I2CConnectionMock()
mock.set_register(R._REG_TMR_CLKOUT_CTRL, 0x38)
rtc = PCF8523Full(mock)
rtc.configure_timer_a('watchdog', 5, '1hz', pulsed=True)
check_true('timer_a_freq', mock.registers[R._REG_TMR_A_FREQ_CTRL] == 0x02)
check_true('timer_a_value', mock.registers[R._REG_TMR_A_REG] == 5)
check_true('timer_a_ctrl', mock.registers[R._REG_TMR_CLKOUT_CTRL] == 0xBC)
rtc.enable_interrupt(SOURCE_TIMER_A)
check_true('timer_a_watchdog_ie', (last_write(mock, R._REG_CONTROL_2) & 0x07) == 0x04)
rtc.disable_timer_a()
check_true('timer_a_disabled', (mock.registers[R._REG_TMR_CLKOUT_CTRL] & 0x06) == 0)

# Timer B: nearest TBW (130 ms -> 125 ms = 100), TBQ, TBC.
mock = I2CConnectionMock()
mock.set_register(R._REG_TMR_CLKOUT_CTRL, 0x38)
rtc = PCF8523Full(mock)
rtc.configure_timer_b(30, '1_60hz', 130)
check_true('timer_b_freq', mock.registers[R._REG_TMR_B_FREQ_CTRL] == 0x43)
check_true('timer_b_enabled', mock.registers[R._REG_TMR_CLKOUT_CTRL] == 0x39)

# CLKOUT: COF field.
mock = I2CConnectionMock()
mock.set_register(R._REG_TMR_CLKOUT_CTRL, 0x00)
rtc = PCF8523Full(mock)
rtc.set_clock_output(1)
check_true('clkout_1hz', mock.registers[R._REG_TMR_CLKOUT_CTRL] == 0x30)
rtc.disable_clock_output()
check_true('clkout_disabled', mock.registers[R._REG_TMR_CLKOUT_CTRL] == 0x38)

# Battery backup: direct mode without low detection -> PM=101.
mock = I2CConnectionMock()
rtc = PCF8523Full(mock)
rtc.configure_battery_backup('direct', low_detection=False)
check_true('battery_backup_direct', (mock.registers[R._REG_CONTROL_3] & 0xE0) == 0xA0)

# poll_interrupt: maps every flag and clears only the flags that were set.
mock = I2CConnectionMock()
rtc = PCF8523Full(mock)
mock.set_register(R._REG_CONTROL_2, 0x80 | 0x20 | 0x08 | 0x03)  # WTAF, CTBF, AF, CTAIE|CTBIE
mock.set_register(R._REG_CONTROL_3, 0x08 | 0x04 | 0x02)         # BSF, BLF, BSIE
mock.writes.clear()
status = rtc.poll_interrupt()
check_true('poll_interrupt_status',
           status == SOURCE_TIMER_A | SOURCE_TIMER_B | SOURCE_ALARM
           | SOURCE_BATTERY_SWITCH | SOURCE_BATTERY_LOW)
check_true('poll_interrupt_not_second', not status & SOURCE_SECOND)
c2 = last_write(mock, R._REG_CONTROL_2)
check_true('poll_interrupt_clears_set_flags', (c2 & 0x28) == 0)
check_true('poll_interrupt_keeps_unset_flags', (c2 & 0x50) == 0x50)
check_true('poll_interrupt_keeps_enables', (c2 & 0x07) == 0x03)
c3 = last_write(mock, R._REG_CONTROL_3)
check_true('poll_interrupt_clears_bsf', c3 == 0x02)

# enable_interrupt: SIE/AIE in CONTROL_1, BLIE in CONTROL_3.
mock = I2CConnectionMock()
rtc = PCF8523Full(mock)
rtc.enable_interrupt(SOURCE_SECOND | SOURCE_ALARM | SOURCE_BATTERY_LOW)
check_true('enable_interrupt_control_1', last_write(mock, R._REG_CONTROL_1) == 0x06)
check_true('enable_interrupt_control_3', (last_write(mock, R._REG_CONTROL_3) & 0x03) == 0x01)
rtc.disable_interrupt(SOURCE_ALARM)
check_true('disable_interrupt_control_1', last_write(mock, R._REG_CONTROL_1) == 0x04)

# software_reset writes 0x58 to CONTROL_1.
mock = I2CConnectionMock()
rtc = PCF8523Full(mock)
rtc.software_reset()
check_true('software_reset_sequence', last_write(mock, R._REG_CONTROL_1) == 0x58)

print('===DONE: %d passed, %d failed===' % (passed, failed))
if failed:
    raise SystemExit(1)
