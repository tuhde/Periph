"""Unit test for the PCF85063A — runs without hardware using the I2C mock.

Verifies BCD conversion, the STOP-bit set_datetime sequence, the init-time
24-hour/STOP defaults, active-low alarm enables, the offset two's-complement
encoding, timer configuration, CLKOUT, and interrupt status mapping/flag
clearing by observing what the driver writes to the mock.
"""

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.rtc.pcf85063a import (
    PCF85063AMinimal, PCF85063AFull,
    SOURCE_ALARM, SOURCE_TIMER, SOURCE_MINUTE, SOURCE_HALF_MINUTE, SOURCE_CORRECTION,
)

passed = 0
failed = 0

R = PCF85063AMinimal


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


# init: clears STOP and 12_24, preserves CIE/CAP_SEL, masks SR; no time write.
mock = I2CConnectionMock()
mock.set_register(R._REG_CONTROL_1, 0x20 | 0x10 | 0x04 | 0x02 | 0x01)
PCF85063AMinimal(mock)
check_true('init_control_1', last_write(mock, R._REG_CONTROL_1) == 0x05)
check_true('init_no_time_write', not [w for w in mock.writes if len(w) > 2])

# get_datetime: 2026-09-23 Wednesday(3) 14:30:45, OS flag set is masked off.
mock = I2CConnectionMock()
mock.set_register(R._REG_SECONDS, 0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26)
rtc = PCF85063AMinimal(mock)
dt = rtc.get_datetime()
check_true('get_datetime_time', dt[4:7] == (14, 30, 45))
check_true('get_datetime_date', dt[0:4] == (2026, 9, 23, 3))

# set_datetime: STOP=1, 7-byte BCD write with OS=0, STOP=0; 12_24 cleared.
mock = I2CConnectionMock()
rtc = PCF85063AMinimal(mock)
mock.set_register(R._REG_CONTROL_1, 0x02)  # 12-hour mode set
mock.writes.clear()
rtc.set_datetime(2026, 9, 23, 3, 14, 30, 45)
ctrl_writes = [w[1] for w in mock.writes if len(w) == 2 and w[0] == R._REG_CONTROL_1]
time_write = [w for w in mock.writes if len(w) == 8 and w[0] == R._REG_SECONDS]
check_true('set_datetime_stop_sequence', ctrl_writes == [0x20, 0x00])
check_true('set_datetime_bcd_payload',
           len(time_write) == 1 and time_write[0][1:] == bytes([0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26]))

# set_datetime rejects years outside 2000-2099.
try:
    rtc.set_datetime(2100, 1, 1, 5, 0, 0, 0)
    check_true('set_datetime_rejects_2100', False)
except ValueError:
    check_true('set_datetime_rejects_2100', True)

# Alarm: None fields disabled (bit 7 set), values BCD with AEN=0; round trip; AF cleared.
mock = I2CConnectionMock()
rtc = PCF85063AFull(mock)
mock.set_register(R._REG_CONTROL_2, 0xC8)  # AIE, AF, TF set
rtc.set_alarm(second=5, minute=45, weekday=6)
check_true('set_alarm_registers',
           [mock.registers[r] for r in range(0x0B, 0x10)] == [0x05, 0x45, 0x80, 0x80, 0x06])
check_true('get_alarm_roundtrip', rtc.get_alarm() == (5, 45, None, None, 6))
c2 = last_write(mock, R._REG_CONTROL_2)
check_true('set_alarm_clears_af_keeps_tf', (c2 & 0x48) == 0x08 and (c2 & 0x80) == 0x80)

# Offset: signed 7-bit round trip, mode bit.
mock = I2CConnectionMock()
rtc = PCF85063AFull(mock)
rtc.set_offset(-64, 'course')
check_true('set_offset_encoding', mock.registers[R._REG_OFFSET] == 0xC0)
check_true('get_offset_roundtrip', rtc.get_offset() == (-64, 'course'))
rtc.set_offset(63)
check_true('get_offset_positive', rtc.get_offset() == (63, 'normal'))

# Timer: value, TCF, TI_TP, TE; TIE through enable_interrupt; disable -> TCF=11.
mock = I2CConnectionMock()
mock.set_register(R._REG_TIMER_MODE, 0x18)
rtc = PCF85063AFull(mock)
rtc.configure_timer(30, '1hz', pulsed=True)
check_true('timer_value', mock.registers[R._REG_TIMER_VALUE] == 30)
check_true('timer_mode', mock.registers[R._REG_TIMER_MODE] == 0x15)  # TCF=10, TE, TI_TP
rtc.enable_interrupt(SOURCE_TIMER)
check_true('timer_tie', mock.registers[R._REG_TIMER_MODE] == 0x17)
rtc.disable_timer()
check_true('timer_disabled', mock.registers[R._REG_TIMER_MODE] == 0x19)  # TCF=11, TE=0, TIE=0, TI_TP kept
mock.registers[R._REG_TIMER_VALUE] = 17
check_true('read_timer', rtc.read_timer() == 17)

# CLKOUT: COF field, flags kept.
mock = I2CConnectionMock()
mock.set_register(R._REG_CONTROL_2, 0x48)
rtc = PCF85063AFull(mock)
rtc.set_clock_output(1)
check_true('clkout_1hz', mock.registers[R._REG_CONTROL_2] == 0x4E)
rtc.disable_clock_output()
check_true('clkout_disabled', (mock.registers[R._REG_CONTROL_2] & 0x07) == 0x07)
rtc.set_clock_output(2048)
check_true('clkout_2048', (mock.registers[R._REG_CONTROL_2] & 0x07) == 0x04)

# Minute / half-minute interrupt enables.
mock = I2CConnectionMock()
rtc = PCF85063AFull(mock)
rtc.set_minute_interrupt('minute')
check_true('minute_mi', (mock.registers[R._REG_CONTROL_2] & 0x30) == 0x20)
rtc.set_minute_interrupt('half_minute')
check_true('half_minute_hmi', (mock.registers[R._REG_CONTROL_2] & 0x30) == 0x10)
rtc.set_minute_interrupt('off')
check_true('minute_off', (mock.registers[R._REG_CONTROL_2] & 0x30) == 0x00)

# Oscillator load, stop/start, OS handling, RAM.
mock = I2CConnectionMock()
rtc = PCF85063AFull(mock)
rtc.set_oscillator_load(12.5)
check_true('load_12_5', mock.registers[R._REG_CONTROL_1] & 0x01)
rtc.set_oscillator_load(7)
check_true('load_7', not mock.registers[R._REG_CONTROL_1] & 0x01)
rtc.stop_clock()
check_true('stop_clock', mock.registers[R._REG_CONTROL_1] & 0x20)
rtc.start_clock()
check_true('start_clock', not mock.registers[R._REG_CONTROL_1] & 0x20)
mock.set_register(R._REG_SECONDS, 0xD9)
check_true('oscillator_stopped', rtc.oscillator_stopped())
rtc.clear_oscillator_stop()
check_true('clear_oscillator_stop', mock.registers[R._REG_SECONDS] == 0x59)
check_true('oscillator_ok', not rtc.oscillator_stopped())
rtc.write_ram(0xA5)
check_true('ram_roundtrip', rtc.read_ram() == 0xA5)

# poll_interrupt: maps flags and clears only the flags that were set.
mock = I2CConnectionMock()
rtc = PCF85063AFull(mock)
mock.set_register(R._REG_CONTROL_2, 0x80 | 0x40 | 0x20 | 0x05)  # AIE, AF, MI, COF=5
mock.writes.clear()
status = rtc.poll_interrupt()
check_true('poll_interrupt_status', status == SOURCE_ALARM)
c2 = last_write(mock, R._REG_CONTROL_2)
check_true('poll_interrupt_clears_af', (c2 & 0x40) == 0)
check_true('poll_interrupt_keeps_tf', (c2 & 0x08) == 0x08)
check_true('poll_interrupt_keeps_enables', (c2 & 0xB7) == 0xA5)
mock.set_register(R._REG_CONTROL_2, 0x08)
check_true('poll_interrupt_timer', rtc.poll_interrupt() == SOURCE_TIMER)
mock.set_register(R._REG_CONTROL_2, 0x00)
mock.writes.clear()
check_true('poll_interrupt_none', rtc.poll_interrupt() == 0 and not mock.writes[1:])

# enable_interrupt: AIE / MI / HMI in CONTROL_2, CIE in CONTROL_1.
mock = I2CConnectionMock()
rtc = PCF85063AFull(mock)
rtc.enable_interrupt(SOURCE_ALARM | SOURCE_MINUTE | SOURCE_HALF_MINUTE | SOURCE_CORRECTION)
check_true('enable_interrupt_control_2', (mock.registers[R._REG_CONTROL_2] & 0xB0) == 0xB0)
check_true('enable_interrupt_control_1', mock.registers[R._REG_CONTROL_1] & 0x04)
rtc.disable_interrupt(SOURCE_ALARM | SOURCE_CORRECTION)
check_true('disable_interrupt_control_2', (mock.registers[R._REG_CONTROL_2] & 0xB0) == 0x30)
check_true('disable_interrupt_control_1', not mock.registers[R._REG_CONTROL_1] & 0x04)

# software_reset writes 0x58 to CONTROL_1.
mock = I2CConnectionMock()
rtc = PCF85063AFull(mock)
rtc.software_reset()
check_true('software_reset_sequence', last_write(mock, R._REG_CONTROL_1) == 0x58)

print('===DONE: %d passed, %d failed===' % (passed, failed))
if failed:
    raise SystemExit(1)
