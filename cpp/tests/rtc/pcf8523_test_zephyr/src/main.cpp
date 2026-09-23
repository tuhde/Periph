#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "PCF8523.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    if (!device_is_ready(i2c_dev)) {
        printk("FAIL device_ready\n");
        return 1;
    }
    I2CConnectionZephyr connection(i2c_dev, PCF8523Minimal::I2C_ADDRESS);
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

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
