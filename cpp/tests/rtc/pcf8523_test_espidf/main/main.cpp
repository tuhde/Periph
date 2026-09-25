// Auto-generated ESP-IDF test for PCF8523.
// Mirrors the Zephyr test for PCF8523; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCF8523.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = PCF8523Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
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

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
