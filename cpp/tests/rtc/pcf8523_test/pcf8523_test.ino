#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, PCF8523Minimal::I2C_ADDRESS);

    PCF8523Minimal rtc(connection);                          // Create PCF8523 driver, (connection)

    PCF8523Minimal::DateTime dt{2026, 9, 23, 3, 12, 0, 0};
    rtc.setDatetime(dt);                                    // Write calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → void

    PCF8523Minimal::DateTime readBack;
    rtc.getDatetime(readBack);                              // Read calendar clock, () → DateTime
    check_true(readBack.year == 2026 && readBack.month == 9 && readBack.day == 23 && readBack.weekday == 3,
               "datetime_roundtrip_date");
    check_true(readBack.hour == 12 && readBack.minute == 0, "datetime_roundtrip_time");

    PCF8523Full rtc_full(connection);                       // Create PCF8523 Full driver, (connection)
    check_true(!rtc_full.oscillatorStopped(), "oscillator_running_after_set_datetime");

    PCF8523Full::Alarm alarm{15, 6, PCF8523Full::ALARM_DISABLED, PCF8523Full::ALARM_DISABLED};
    rtc_full.setAlarm(alarm);                               // Configure alarm, (minute, hour, day, weekday) → void
    PCF8523Full::Alarm alarmBack;
    rtc_full.getAlarm(alarmBack);                           // Read alarm, () → Alarm
    check_true(alarmBack.minute == 15 && alarmBack.hour == 6 && alarmBack.day == PCF8523Full::ALARM_DISABLED
               && alarmBack.weekday == PCF8523Full::ALARM_DISABLED, "alarm_roundtrip");
    PCF8523Full::Alarm off{PCF8523Full::ALARM_DISABLED, PCF8523Full::ALARM_DISABLED,
                           PCF8523Full::ALARM_DISABLED, PCF8523Full::ALARM_DISABLED};
    rtc_full.setAlarm(off);

    rtc_full.setOffset(-3, PCF8523Full::OffsetMode::EveryMinute);  // Write offset calibration, (offset, mode) → void
    int8_t offset;
    PCF8523Full::OffsetMode mode;
    rtc_full.getOffset(offset, mode);                       // Read offset calibration, () → (int8_t, OffsetMode)
    check_true(offset == -3 && mode == PCF8523Full::OffsetMode::EveryMinute, "offset_roundtrip");
    rtc_full.setOffset(0);

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
