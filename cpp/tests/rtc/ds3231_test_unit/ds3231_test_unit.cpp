#include <stdio.h>
#include <stdint.h>
#include <cmath>
#include "I2CConnectionMock.h"
#include "DS3231.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static const uint8_t REG_SECONDS = 0x00;
static const uint8_t REG_HOURS   = 0x02;
static const uint8_t REG_CONTROL = 0x0E;
static const uint8_t REG_STATUS  = 0x0F;
static const uint8_t REG_AGING   = 0x10;
static const uint8_t REG_TEMP_MSB = 0x11;

int main() {
    // getDatetime: 2026-09-22 Tuesday 14:30:45, 24-hour mode.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_SECONDS, {0x45, 0x30, 0x14, 0x02, 0x22, 0x09, 0x26});
        DS3231Minimal rtc(mock);
        DS3231Minimal::DateTime dt;
        rtc.getDatetime(dt);
        check_true(dt.second == 45 && dt.minute == 30 && dt.hour == 14, "get_datetime_time");
        check_true(dt.weekday == 2 && dt.day == 22 && dt.month == 9 && dt.year == 2026, "get_datetime_date");
    }

    // getDatetime: 12-hour mode, 11 PM -> 23:00.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_SECONDS, {0x00, 0x00, 0x71, 0x01, 0x01, 0x01, 0x25});
        DS3231Minimal rtc(mock);
        DS3231Minimal::DateTime dt;
        rtc.getDatetime(dt);
        check_true(dt.hour == 23, "get_datetime_12h_pm_decode");
    }

    // setDatetime: forces 24-hour mode and clears OSF.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_STATUS, {0x80});  // OSF set
        DS3231Minimal rtc(mock);
        DS3231Minimal::DateTime dt{2026, 9, 22, 2, 14, 30, 45};
        rtc.setDatetime(dt);
        bool hour_ok = false, osf_cleared = false;
        for (const auto& w : mock.writes()) {
            if (w.size() >= 3 && w[0] == REG_SECONDS && w[3] == 0x14) hour_ok = true;
            if (w.size() == 2 && w[0] == REG_STATUS && w[1] == 0x00) osf_cleared = true;
        }
        check_true(hour_ok, "set_datetime_writes_24h_bcd_hour");
        check_true(osf_cleared, "set_datetime_clears_osf");
    }

    // readTemperature: +25.25 C (MSB=0x19, LSB=0x40).
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_TEMP_MSB, {0x19, 0x40});
        DS3231Minimal rtc(mock);
        check_true(std::fabs(rtc.readTemperature() - 25.25f) < 1e-6f, "read_temperature_positive");
    }

    // readTemperature: negative, -10.25 C (MSB=0xF6 = -10, LSB fractional bits=01 -> 0.25).
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_TEMP_MSB, {0xF6, 0x40});
        DS3231Minimal rtc(mock);
        check_true(std::fabs(rtc.readTemperature() - (-9.75f)) < 1e-6f, "read_temperature_negative");
    }

    // Alarm1 match-mode round trip: MATCH_HOURS_MINUTES_SECONDS.
    {
        I2CConnectionMock mock;
        DS3231Full rtc(mock);
        DS3231Full::Alarm1 a{15, 30, 9, 0, false, DS3231Full::ALARM1_MATCH_HOURS_MINUTES_SECONDS};
        rtc.setAlarm1(a);
        DS3231Full::Alarm1 got;
        rtc.getAlarm1(got);
        check_true(got.matchMode == DS3231Full::ALARM1_MATCH_HOURS_MINUTES_SECONDS, "alarm1_match_mode_roundtrip");
        check_true(got.second == 15 && got.minute == 30 && got.hour == 9, "alarm1_fields_roundtrip");
    }

    // Alarm1 match-mode round trip: MATCH_DAY (day-of-week).
    {
        I2CConnectionMock mock;
        DS3231Full rtc(mock);
        DS3231Full::Alarm1 a{0, 0, 0, 3, true, DS3231Full::ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS};
        rtc.setAlarm1(a);
        DS3231Full::Alarm1 got;
        rtc.getAlarm1(got);
        check_true(got.matchMode == DS3231Full::ALARM1_MATCH_DAY_HOURS_MINUTES_SECONDS, "alarm1_day_mode_roundtrip");
        check_true(got.isDayOfWeek && got.dayOrDate == 3, "alarm1_day_fields_roundtrip");
    }

    // Alarm2 match-mode round trip: EVERY_MINUTE.
    {
        I2CConnectionMock mock;
        DS3231Full rtc(mock);
        DS3231Full::Alarm2 a{0, 0, 0, false, DS3231Full::ALARM2_EVERY_MINUTE};
        rtc.setAlarm2(a);
        DS3231Full::Alarm2 got;
        rtc.getAlarm2(got);
        check_true(got.matchMode == DS3231Full::ALARM2_EVERY_MINUTE, "alarm2_every_minute_roundtrip");
    }

    // Interrupt status masking: poll_interrupt clears A1F/A2F, leaves OSF/EN32kHz.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_STATUS, {(uint8_t)(0x80 | 0x08 | 0x01 | 0x02)});  // OSF, EN32kHz, A1F, A2F all set
        DS3231Full rtc(mock);
        uint8_t status = rtc.pollInterrupt();
        check_true((status & DS3231Full::SOURCE_ALARM1) != 0, "poll_interrupt_reports_alarm1");
        check_true((status & DS3231Full::SOURCE_ALARM2) != 0, "poll_interrupt_reports_alarm2");
        uint8_t after = 0;
        for (const auto& w : mock.writes()) if (w.size() == 2 && w[0] == REG_STATUS) after = w[1];
        check_true((after & 0x01) == 0 && (after & 0x02) == 0, "poll_interrupt_clears_alarm_flags");
        check_true((after & 0x80) != 0 && (after & 0x08) != 0, "poll_interrupt_preserves_osf_en32khz");
    }

    // enableInterrupt sets INTCN + A1IE/A2IE.
    {
        I2CConnectionMock mock;
        DS3231Full rtc(mock);
        rtc.enableInterrupt(DS3231Full::SOURCE_ALARM1 | DS3231Full::SOURCE_ALARM2);
        uint8_t ctrl = 0;
        for (const auto& w : mock.writes()) if (w.size() == 2 && w[0] == REG_CONTROL) ctrl = w[1];
        check_true((ctrl & 0x04) && (ctrl & 0x01) && (ctrl & 0x02), "enable_interrupt_sets_intcn_and_both_ie_bits");
    }

    // Aging offset: signed round trip.
    {
        I2CConnectionMock mock;
        mock.setRegister(REG_AGING, {(uint8_t)(-5)});
        DS3231Full rtc(mock);
        check_true(rtc.getAgingOffset() == -5, "aging_offset_signed_read");
        rtc.setAgingOffset(-100);
        uint8_t raw = 0;
        for (const auto& w : mock.writes()) if (w.size() == 2 && w[0] == REG_AGING) raw = w[1];
        check_true((int8_t)raw == -100, "aging_offset_signed_write");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
