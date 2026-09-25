#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "DS3231.h"

// Backup-clock module for a data logger: reseeds the clock after a power
// loss, then logs a "reading" on every once-per-minute and once-per-hour
// alarm match, using the on-chip temperature sensor as the payload.

static volatile bool alarm1Fired = false, alarm2Fired = false;

static void onAlarm(uint8_t status) {
    if (status & DS3231Full::SOURCE_ALARM1) alarm1Fired = true;
    if (status & DS3231Full::SOURCE_ALARM2) alarm2Fired = true;
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
    dev_cfg.device_address = DS3231Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    DS3231Full rtc(connection);

    // --- Detect a lost power reference and reseed if needed ---
    // A fresh chip, or one whose backup coin cell died, reports the
    // Oscillator Stop Flag set - its clock/calendar registers cannot be
    // trusted until reseeded from a known-good reference.
    if (rtc.oscillatorStopped()) {
        DS3231Minimal::DateTime reference{2026, 1, 1, 4, 0, 0, 0};
        rtc.setDatetime(reference);  // also clears the Oscillator Stop Flag
        printf("oscillator was stopped - reseeded from reference timestamp\n");
    }

    // --- Arm a once-per-minute log tick and a once-per-hour summary tick ---
    DS3231Full::Alarm1 perMinute{0, 0, 0, 0, false, DS3231Full::ALARM1_MATCH_SECONDS};
    rtc.setAlarm1(perMinute);
    DS3231Full::Alarm2 hourly{0, 0, 0, false, DS3231Full::ALARM2_MATCH_MINUTES};
    rtc.setAlarm2(hourly);

    rtc.onInterrupt(onAlarm);
    rtc.enableInterrupt(DS3231Full::SOURCE_ALARM1 | DS3231Full::SOURCE_ALARM2);

    int logged = 0;
    while (logged < 5) {
        if (alarm1Fired || alarm2Fired) {
            DS3231Minimal::DateTime dt;
            rtc.getDatetime(dt);
            float tempC = rtc.readTemperature();
            if (alarm1Fired) {
                printf("[minute] %02u:%02u:%02u  %.2f C\n", dt.hour, dt.minute, dt.second, (double)tempC);
                logged++;
            }
            if (alarm2Fired) {
                printf("[hourly] %02u:%02u  %.2f C\n", dt.hour, dt.minute, (double)tempC);
            }
            alarm1Fired = false;
            alarm2Fired = false;
        }
        vTaskDelay(pdMS_TO_TICKS(200));
    }

    rtc.disableInterrupt(DS3231Full::SOURCE_ALARM1 | DS3231Full::SOURCE_ALARM2);
    rtc.offInterrupt();
}
