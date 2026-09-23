#include <stdio.h>
#include <stdint.h>
#include "I2CConnectionMock.h"
#include "PCF8523.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static const uint8_t REG_CONTROL_1       = 0x00;
static const uint8_t REG_CONTROL_2       = 0x01;
static const uint8_t REG_CONTROL_3       = 0x02;
static const uint8_t REG_SECONDS         = 0x03;
static const uint8_t REG_MINUTE_ALARM    = 0x0A;
static const uint8_t REG_OFFSET          = 0x0E;
static const uint8_t REG_TMR_CLKOUT_CTRL = 0x0F;
static const uint8_t REG_TMR_A_FREQ_CTRL = 0x10;
static const uint8_t REG_TMR_A_REG       = 0x11;
static const uint8_t REG_TMR_B_FREQ_CTRL = 0x12;

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
    // Constructor writes CONTROL_3 = 0x00 (PM=000, standard switch-over).
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_CONTROL_3, {0xE0});
        PCF8523Minimal rtc(mock);
        check_true(lastWrite(mock, REG_CONTROL_3) == 0x00, "init_sets_pm_standard");
    }

    // getDatetime: 2026-09-23 Wednesday(3) 14:30:45, OS flag masked off.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_SECONDS, {0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26});
        PCF8523Minimal rtc(mock);
        PCF8523Minimal::DateTime dt;
        rtc.getDatetime(dt);
        check_true(dt.second == 45 && dt.minute == 30 && dt.hour == 14, "get_datetime_time");
        check_true(dt.year == 2026 && dt.month == 9 && dt.day == 23 && dt.weekday == 3, "get_datetime_date");
    }

    // setDatetime: STOP=1, 7-byte BCD write with OS=0, STOP=0; 12_24 cleared.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_CONTROL_1, {0x08});
        PCF8523Minimal rtc(mock);
        size_t before = mock.writes().size();
        PCF8523Minimal::DateTime dt{2026, 9, 23, 3, 14, 30, 45};
        rtc.setDatetime(dt);
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
    }

    // Alarm: disabled fields set bit 7; round trip.
    {
        I2CConnectionMock mock;
        PCF8523Full rtc(mock);
        PCF8523Full::Alarm a{45, PCF8523Full::ALARM_DISABLED, PCF8523Full::ALARM_DISABLED, 6};
        rtc.setAlarm(a);
        check_true(regValue(mock, REG_MINUTE_ALARM) == 0x45 && regValue(mock, REG_MINUTE_ALARM + 1) == 0x80
                   && regValue(mock, REG_MINUTE_ALARM + 2) == 0x80 && regValue(mock, REG_MINUTE_ALARM + 3) == 0x06,
                   "set_alarm_registers");
        PCF8523Full::Alarm b;
        rtc.getAlarm(b);
        check_true(b.minute == 45 && b.hour == PCF8523Full::ALARM_DISABLED && b.day == PCF8523Full::ALARM_DISABLED
                   && b.weekday == 6, "get_alarm_roundtrip");
    }

    // Offset: signed 7-bit round trip.
    {
        I2CConnectionMock mock;
        PCF8523Full rtc(mock);
        rtc.setOffset(-64, PCF8523Full::OffsetMode::EveryMinute);
        check_true(regValue(mock, REG_OFFSET) == 0xC0, "set_offset_encoding");
        int8_t off; PCF8523Full::OffsetMode mode;
        rtc.getOffset(off, mode);
        check_true(off == -64 && mode == PCF8523Full::OffsetMode::EveryMinute, "get_offset_roundtrip");
        rtc.setOffset(63);
        rtc.getOffset(off, mode);
        check_true(off == 63 && mode == PCF8523Full::OffsetMode::EveryTwoHours, "get_offset_positive");
    }

    // Timer A watchdog: TAQ, T_A, TAC=10, TAM; enableInterrupt picks WTAIE.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_TMR_CLKOUT_CTRL, {0x38});
        PCF8523Full rtc(mock);
        rtc.configureTimerA(PCF8523Full::TimerAMode::Watchdog, 5, PCF8523Full::SourceClock::Hz1, true);
        check_true(regValue(mock, REG_TMR_A_FREQ_CTRL) == 0x02, "timer_a_freq");
        check_true(regValue(mock, REG_TMR_A_REG) == 5, "timer_a_value");
        check_true(regValue(mock, REG_TMR_CLKOUT_CTRL) == 0xBC, "timer_a_ctrl");
        rtc.enableInterrupt(PCF8523Full::SOURCE_TIMER_A);
        check_true((lastWrite(mock, REG_CONTROL_2) & 0x07) == 0x04, "timer_a_watchdog_ie");
        rtc.disableTimerA();
        check_true((regValue(mock, REG_TMR_CLKOUT_CTRL) & 0x06) == 0, "timer_a_disabled");
    }

    // Timer B: nearest TBW (130 ms -> 125 ms), TBQ, TBC.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_TMR_CLKOUT_CTRL, {0x38});
        PCF8523Full rtc(mock);
        rtc.configureTimerB(30, PCF8523Full::SourceClock::Hz1_60, 130.0f);
        check_true(regValue(mock, REG_TMR_B_FREQ_CTRL) == 0x43, "timer_b_freq");
        check_true(regValue(mock, REG_TMR_CLKOUT_CTRL) == 0x39, "timer_b_enabled");
    }

    // CLKOUT.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_TMR_CLKOUT_CTRL, {0x00});
        PCF8523Full rtc(mock);
        rtc.setClockOutput(1);
        check_true(regValue(mock, REG_TMR_CLKOUT_CTRL) == 0x30, "clkout_1hz");
        rtc.disableClockOutput();
        check_true(regValue(mock, REG_TMR_CLKOUT_CTRL) == 0x38, "clkout_disabled");
    }

    // Battery backup: direct mode without low detection -> PM=101.
    {
        I2CConnectionMock mock;
        PCF8523Full rtc(mock);
        rtc.configureBatteryBackup(PCF8523Full::BatteryMode::Direct, false);
        check_true((regValue(mock, REG_CONTROL_3) & 0xE0) == 0xA0, "battery_backup_direct");
    }

    // pollInterrupt: maps every flag and clears only the flags that were set.
    {
        I2CConnectionMock mock;
        PCF8523Full rtc(mock);
        mock.setRegister(REG_CONTROL_2, {0x80 | 0x20 | 0x08 | 0x03});
        mock.setRegister(REG_CONTROL_3, {0x08 | 0x04 | 0x02});
        uint8_t status = rtc.pollInterrupt();
        check_true(status == (PCF8523Full::SOURCE_TIMER_A | PCF8523Full::SOURCE_TIMER_B | PCF8523Full::SOURCE_ALARM
                              | PCF8523Full::SOURCE_BATTERY_SWITCH | PCF8523Full::SOURCE_BATTERY_LOW),
                   "poll_interrupt_status");
        int c2 = lastWrite(mock, REG_CONTROL_2);
        check_true((c2 & 0x28) == 0, "poll_interrupt_clears_set_flags");
        check_true((c2 & 0x50) == 0x50, "poll_interrupt_keeps_unset_flags");
        check_true((c2 & 0x07) == 0x03, "poll_interrupt_keeps_enables");
        check_true(lastWrite(mock, REG_CONTROL_3) == 0x02, "poll_interrupt_clears_bsf");
    }

    // enableInterrupt: SIE/AIE in CONTROL_1, BLIE in CONTROL_3.
    {
        I2CConnectionMock mock;
        PCF8523Full rtc(mock);
        rtc.enableInterrupt(PCF8523Full::SOURCE_SECOND | PCF8523Full::SOURCE_ALARM | PCF8523Full::SOURCE_BATTERY_LOW);
        check_true(lastWrite(mock, REG_CONTROL_1) == 0x06, "enable_interrupt_control_1");
        check_true((lastWrite(mock, REG_CONTROL_3) & 0x03) == 0x01, "enable_interrupt_control_3");
        rtc.disableInterrupt(PCF8523Full::SOURCE_ALARM);
        check_true(lastWrite(mock, REG_CONTROL_1) == 0x04, "disable_interrupt_control_1");
    }

    // softwareReset writes 0x58 to CONTROL_1.
    {
        I2CConnectionMock mock;
        PCF8523Full rtc(mock);
        rtc.softwareReset();
        check_true(lastWrite(mock, REG_CONTROL_1) == 0x58, "software_reset_sequence");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
