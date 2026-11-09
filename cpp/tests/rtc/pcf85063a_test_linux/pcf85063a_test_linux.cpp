#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif

#include <stdio.h>
#include "I2CConnectionLinux.h"
#include "PCF85063A.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, PCF85063AMinimal::I2C_ADDRESS);

    PCF85063AMinimal rtc(connection);                        // Create PCF85063A driver, (connection)

    PCF85063AMinimal::DateTime dt{2026, 9, 23, 3, 12, 0, 0};
    rtc.setDatetime(dt);                                    // Write calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → bool

    PCF85063AMinimal::DateTime readBack;
    rtc.getDatetime(readBack);                              // Read calendar clock, () → DateTime
    check_true(readBack.year == 2026 && readBack.month == 9 && readBack.day == 23 && readBack.weekday == 3,
               "datetime_roundtrip_date");
    check_true(readBack.hour == 12 && readBack.minute == 0, "datetime_roundtrip_time");

    PCF85063AFull rtc_full(connection);                     // Create PCF85063A Full driver, (connection)
    check_true(!rtc_full.oscillatorStopped(), "oscillator_running_after_set_datetime");

    PCF85063AFull::Alarm alarm{0, 15, 6, PCF85063AFull::ALARM_DISABLED, PCF85063AFull::ALARM_DISABLED};
    rtc_full.setAlarm(alarm);                               // Configure alarm, (second, minute, hour, day, weekday) → void
    PCF85063AFull::Alarm alarmBack;
    rtc_full.getAlarm(alarmBack);                           // Read alarm, () → Alarm
    check_true(alarmBack.second == 0 && alarmBack.minute == 15 && alarmBack.hour == 6
               && alarmBack.day == PCF85063AFull::ALARM_DISABLED
               && alarmBack.weekday == PCF85063AFull::ALARM_DISABLED, "alarm_roundtrip");
    PCF85063AFull::Alarm off{PCF85063AFull::ALARM_DISABLED, PCF85063AFull::ALARM_DISABLED,
                             PCF85063AFull::ALARM_DISABLED, PCF85063AFull::ALARM_DISABLED,
                             PCF85063AFull::ALARM_DISABLED};
    rtc_full.setAlarm(off);

    rtc_full.setOffset(-3, PCF85063AFull::OffsetMode::Course);  // Write offset calibration, (offset, mode) → void
    int8_t offset;
    PCF85063AFull::OffsetMode mode;
    rtc_full.getOffset(offset, mode);                       // Read offset calibration, () → (int8_t, OffsetMode)
    check_true(offset == -3 && mode == PCF85063AFull::OffsetMode::Course, "offset_roundtrip");
    rtc_full.setOffset(0);

    rtc_full.writeRam(0xA5);                                // Write RAM byte, (value 0–255) → void
    check_true(rtc_full.readRam() == 0xA5, "ram_roundtrip");  // Read RAM byte, () → uint8_t

    rtc_full.configureTimer(30, PCF85063AFull::SourceClock::Hz1);  // Start countdown timer, (value, sourceClock, pulsed=false) → void
    uint8_t remaining = rtc_full.readTimer();               // Read timer counter, () → uint8_t
    check_true(remaining > 0 && remaining <= 30, "timer_running");
    rtc_full.disableTimer();                                // Stop timer, () → void

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
