#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif

#include <stdio.h>
#include "I2CConnectionLinux.h"
#include "DS3231.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, DS3231Minimal::I2C_ADDRESS);

    DS3231Minimal rtc(connection);                          // Create DS3231 driver, (connection)

    DS3231Minimal::DateTime dt{2026, 9, 22, 2, 12, 0, 0};
    rtc.setDatetime(dt);                                    // Write calendar clock, (year, month, day, weekday, hour, minute, second) → None

    DS3231Minimal::DateTime readBack;
    rtc.getDatetime(readBack);                              // Read calendar clock, () → DateTime
    check_true(readBack.year == 2026 && readBack.month == 9 && readBack.day == 22, "datetime_roundtrip_date");
    check_true(readBack.hour == 12 && readBack.minute == 0, "datetime_roundtrip_time");

    float tempC = rtc.readTemperature();                    // Read on-chip temperature, () → float C
    check_true(tempC > -40.0f && tempC < 85.0f, "temperature_in_range");

    DS3231Full rtc_full(connection);                        // Create DS3231 Full driver, (connection)
    check_true(!rtc_full.oscillatorStopped(), "oscillator_running_after_set_datetime");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
