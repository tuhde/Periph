#include <stdio.h>
#include <stdint.h>
#include "I2CConnectionMock.h"
#include "PCF85063A.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static const uint8_t REG_CONTROL_1    = 0x00;
static const uint8_t REG_CONTROL_2    = 0x01;
static const uint8_t REG_OFFSET       = 0x02;
static const uint8_t REG_RAM          = 0x03;
static const uint8_t REG_SECONDS      = 0x04;
static const uint8_t REG_SECOND_ALARM = 0x0B;
static const uint8_t REG_TIMER_VALUE  = 0x10;
static const uint8_t REG_TIMER_MODE   = 0x11;

static int lastWrite(const I2CConnectionMock& mock, uint8_t reg) {
    int value = -1;
    for (const auto& w : mock.writes())
        if (w.size() == 2 && w[0] == reg) value = w[1];
    return value;
}

static int regValue(const I2CConnectionMock& mock, uint8_t reg) {
    auto it = mock.registers().find(reg);
    return it != mock.registers().end() ? it->second : 0;
}

int main() {
    // Constructor: clears STOP and 12_24, keeps CIE/CAP_SEL, masks SR; writes no time register.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_CONTROL_1, {0x20 | 0x10 | 0x04 | 0x02 | 0x01});
        PCF85063AMinimal rtc(mock);
        check_true(lastWrite(mock, REG_CONTROL_1) == 0x05, "init_control_1");
        bool timeWrite = false;
        for (const auto& w : mock.writes()) if (w.size() > 2) timeWrite = true;
        check_true(!timeWrite, "init_no_time_write");
    }

    // getDatetime: 2026-09-23 Wednesday(3) 14:30:45, OS flag masked off.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_SECONDS, {0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26});
        PCF85063AMinimal rtc(mock);
        PCF85063AMinimal::DateTime dt;
        rtc.getDatetime(dt);
        check_true(dt.second == 45 && dt.minute == 30 && dt.hour == 14, "get_datetime_time");
        check_true(dt.year == 2026 && dt.month == 9 && dt.day == 23 && dt.weekday == 3, "get_datetime_date");
    }

    // setDatetime: STOP=1, 7-byte BCD write with OS=0, STOP=0; 12_24 cleared.
    {
        I2CConnectionMock mock;
        PCF85063AMinimal rtc(mock);
        mock.setRegister(REG_CONTROL_1, {0x02});
        size_t before = mock.writes().size();
        PCF85063AMinimal::DateTime dt{2026, 9, 23, 3, 14, 30, 45};
        check_true(rtc.setDatetime(dt), "set_datetime_accepts_2026");
        int ctrl[4], nctrl = 0;
        bool payload = false;
        const uint8_t expected[7] = {0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26};
        for (size_t k = before; k < mock.writes().size(); ++k) {
            const auto& w = mock.writes()[k];
            if (w.size() == 2 && w[0] == REG_CONTROL_1 && nctrl < 4) ctrl[nctrl++] = w[1];
            if (w.size() == 8 && w[0] == REG_SECONDS) {
                payload = true;
                for (int i = 0; i < 7; ++i) if (w[1 + i] != expected[i]) payload = false;
            }
        }
        check_true(nctrl == 2 && ctrl[0] == 0x20 && ctrl[1] == 0x00, "set_datetime_stop_sequence");
        check_true(payload, "set_datetime_bcd_payload");
        size_t n = mock.writes().size();
        PCF85063AMinimal::DateTime bad{2100, 1, 1, 5, 0, 0, 0};
        check_true(!rtc.setDatetime(bad) && mock.writes().size() == n, "set_datetime_rejects_2100");
    }

    // Alarm: disabled fields set bit 7; round trip; AF cleared, TF kept.
    {
        I2CConnectionMock mock;
        PCF85063AFull rtc(mock);
        mock.setRegister(REG_CONTROL_2, {0xC8});
        const uint8_t D = PCF85063AFull::ALARM_DISABLED;
        PCF85063AFull::Alarm a{5, 45, D, D, 6};
        rtc.setAlarm(a);
        check_true(regValue(mock, REG_SECOND_ALARM) == 0x05 && regValue(mock, REG_SECOND_ALARM + 1) == 0x45
                   && regValue(mock, REG_SECOND_ALARM + 2) == 0x80 && regValue(mock, REG_SECOND_ALARM + 3) == 0x80
                   && regValue(mock, REG_SECOND_ALARM + 4) == 0x06, "set_alarm_registers");
        PCF85063AFull::Alarm b;
        rtc.getAlarm(b);
        check_true(b.second == 5 && b.minute == 45 && b.hour == D && b.day == D && b.weekday == 6, "get_alarm_roundtrip");
        int c2 = lastWrite(mock, REG_CONTROL_2);
        check_true((c2 & 0x48) == 0x08 && (c2 & 0x80) == 0x80, "set_alarm_clears_af_keeps_tf");
    }

    // Offset: signed 7-bit round trip, mode bit.
    {
        I2CConnectionMock mock;
        PCF85063AFull rtc(mock);
        rtc.setOffset(-64, PCF85063AFull::OffsetMode::Course);
        check_true(regValue(mock, REG_OFFSET) == 0xC0, "set_offset_encoding");
        int8_t off; PCF85063AFull::OffsetMode mode;
        rtc.getOffset(off, mode);
        check_true(off == -64 && mode == PCF85063AFull::OffsetMode::Course, "get_offset_roundtrip");
        rtc.setOffset(63);
        rtc.getOffset(off, mode);
        check_true(off == 63 && mode == PCF85063AFull::OffsetMode::Normal, "get_offset_positive");
    }

    // Timer: value, TCF, TI_TP, TE; TIE via enableInterrupt; disable -> TCF=11.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_TIMER_MODE, {0x18});
        PCF85063AFull rtc(mock);
        rtc.configureTimer(30, PCF85063AFull::SourceClock::Hz1, true);
        check_true(regValue(mock, REG_TIMER_VALUE) == 30, "timer_value");
        check_true(regValue(mock, REG_TIMER_MODE) == 0x15, "timer_mode");
        rtc.enableInterrupt(PCF85063AFull::SOURCE_TIMER);
        check_true(regValue(mock, REG_TIMER_MODE) == 0x17, "timer_tie");
        rtc.disableTimer();
        check_true(regValue(mock, REG_TIMER_MODE) == 0x19, "timer_disabled");
        mock.setRegister(REG_TIMER_VALUE, {17});
        check_true(rtc.readTimer() == 17, "read_timer");
    }

    // CLKOUT: COF field, flags kept.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_CONTROL_2, {0x48});
        PCF85063AFull rtc(mock);
        rtc.setClockOutput(1);
        check_true(regValue(mock, REG_CONTROL_2) == 0x4E, "clkout_1hz");
        rtc.disableClockOutput();
        check_true((regValue(mock, REG_CONTROL_2) & 0x07) == 0x07, "clkout_disabled");
        rtc.setClockOutput(2048);
        check_true((regValue(mock, REG_CONTROL_2) & 0x07) == 0x04, "clkout_2048");
    }

    // Minute / half-minute interrupt enables.
    {
        I2CConnectionMock mock;
        PCF85063AFull rtc(mock);
        rtc.setMinuteInterrupt(PCF85063AFull::MinuteInterrupt::Minute);
        check_true((regValue(mock, REG_CONTROL_2) & 0x30) == 0x20, "minute_mi");
        rtc.setMinuteInterrupt(PCF85063AFull::MinuteInterrupt::HalfMinute);
        check_true((regValue(mock, REG_CONTROL_2) & 0x30) == 0x10, "half_minute_hmi");
        rtc.setMinuteInterrupt(PCF85063AFull::MinuteInterrupt::Off);
        check_true((regValue(mock, REG_CONTROL_2) & 0x30) == 0x00, "minute_off");
    }

    // Oscillator load, stop/start, OS handling, RAM.
    {
        I2CConnectionMock mock;
        PCF85063AFull rtc(mock);
        rtc.setOscillatorLoad(12.5f);
        check_true(regValue(mock, REG_CONTROL_1) & 0x01, "load_12_5");
        rtc.setOscillatorLoad(7.0f);
        check_true(!(regValue(mock, REG_CONTROL_1) & 0x01), "load_7");
        rtc.stopClock();
        check_true(regValue(mock, REG_CONTROL_1) & 0x20, "stop_clock");
        rtc.startClock();
        check_true(!(regValue(mock, REG_CONTROL_1) & 0x20), "start_clock");
        mock.setRegister(REG_SECONDS, {0xD9});
        check_true(rtc.oscillatorStopped(), "oscillator_stopped");
        rtc.clearOscillatorStop();
        check_true(regValue(mock, REG_SECONDS) == 0x59, "clear_oscillator_stop");
        check_true(!rtc.oscillatorStopped(), "oscillator_ok");
        rtc.writeRam(0xA5);
        check_true(regValue(mock, REG_RAM) == 0xA5 && rtc.readRam() == 0xA5, "ram_roundtrip");
    }

    // pollInterrupt: maps flags and clears only the flags that were set.
    {
        I2CConnectionMock mock;
        PCF85063AFull rtc(mock);
        mock.setRegister(REG_CONTROL_2, {0x80 | 0x40 | 0x20 | 0x05});
        uint8_t status = rtc.pollInterrupt();
        check_true(status == PCF85063AFull::SOURCE_ALARM, "poll_interrupt_status");
        int c2 = lastWrite(mock, REG_CONTROL_2);
        check_true((c2 & 0x40) == 0, "poll_interrupt_clears_af");
        check_true((c2 & 0x08) == 0x08, "poll_interrupt_keeps_tf");
        check_true((c2 & 0xB7) == 0xA5, "poll_interrupt_keeps_enables");
        mock.setRegister(REG_CONTROL_2, {0x08});
        check_true(rtc.pollInterrupt() == PCF85063AFull::SOURCE_TIMER, "poll_interrupt_timer");
        mock.setRegister(REG_CONTROL_2, {0x00});
        check_true(rtc.pollInterrupt() == 0, "poll_interrupt_none");
    }

    // enableInterrupt: AIE / MI / HMI in Control_2, CIE in Control_1.
    {
        I2CConnectionMock mock;
        PCF85063AFull rtc(mock);
        rtc.enableInterrupt(PCF85063AFull::SOURCE_ALARM | PCF85063AFull::SOURCE_MINUTE
                            | PCF85063AFull::SOURCE_HALF_MINUTE | PCF85063AFull::SOURCE_CORRECTION);
        check_true((regValue(mock, REG_CONTROL_2) & 0xB0) == 0xB0, "enable_interrupt_control_2");
        check_true(regValue(mock, REG_CONTROL_1) & 0x04, "enable_interrupt_control_1");
        rtc.disableInterrupt(PCF85063AFull::SOURCE_ALARM | PCF85063AFull::SOURCE_CORRECTION);
        check_true((regValue(mock, REG_CONTROL_2) & 0xB0) == 0x30, "disable_interrupt_control_2");
        check_true(!(regValue(mock, REG_CONTROL_1) & 0x04), "disable_interrupt_control_1");
    }

    // softwareReset writes 0x58 to Control_1.
    {
        I2CConnectionMock mock;
        PCF85063AFull rtc(mock);
        rtc.softwareReset();
        check_true(lastWrite(mock, REG_CONTROL_1) == 0x58, "software_reset_sequence");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
