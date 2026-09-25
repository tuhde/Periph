#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "DS3231.h"

static void onAlarm(uint8_t status) {
    printf("alarm status=0x%02X\n", status);
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
    DS3231Full rtc(connection);                             // Create DS3231 Full driver, (connection)

    DS3231Minimal::DateTime dt{2026, 9, 22, 2, 14, 30, 0};
    rtc.setDatetime(dt);                                    // Write calendar clock, (year, month, day, weekday, hour, minute, second) → None
                                                              // also forces 24-hour mode and clears the Oscillator Stop Flag
    rtc.getDatetime(dt);                                    // Read calendar clock, () → DateTime
    float tempC = rtc.readTemperature();                    // Read last temperature conversion, () → float C
                                                              // no forced conversion; may be up to 64 s stale

    rtc.forceTemperatureConversion();                       // Force a fresh conversion, () → None
                                                              // sets CONV and blocks until BSY clears (max 200 ms)

    DS3231Full::Alarm1 a1{0, 30, 9, 0, false, DS3231Full::ALARM1_MATCH_HOURS_MINUTES_SECONDS};
    rtc.setAlarm1(a1);                                      // Configure Alarm 1, (second, minute, hour, dayOrDate, isDayOfWeek, matchMode) → None
    DS3231Full::Alarm1 got1;
    rtc.getAlarm1(got1);                                    // Read Alarm 1 configuration, () → Alarm1

    DS3231Full::Alarm2 a2{0, 0, 0, false, DS3231Full::ALARM2_EVERY_MINUTE};
    rtc.setAlarm2(a2);                                      // Configure Alarm 2, (minute, hour, dayOrDate, isDayOfWeek, matchMode) → None
    DS3231Full::Alarm2 got2;
    rtc.getAlarm2(got2);                                    // Read Alarm 2 configuration, () → Alarm2

    rtc.onInterrupt(onAlarm);                                // Subscribe to alarm interrupts, (callback, intPin=nullptr) → None
    rtc.enableInterrupt(DS3231Full::SOURCE_ALARM1 | DS3231Full::SOURCE_ALARM2);  // Enable alarm sources, (source) → None
    uint8_t status = rtc.pollInterrupt();                   // Poll & clear alarm flags, () → uint8_t
    rtc.disableInterrupt(DS3231Full::SOURCE_ALARM1);        // Disable one alarm source, (source) → None
    rtc.offInterrupt();                                     // Unsubscribe, () → None

    rtc.enableSquareWave(8192, false);                      // Enable square wave, (rateHz=8192, batteryBacked=false) → None
    rtc.disableSquareWave();                                // Return INT/SQW to interrupt mode, () → None

    rtc.enable32kHzOutput();                                // Enable 32kHz output, () → None
    bool en32 = rtc.is32kHzEnabled();                       // Query 32kHz output, () → bool
    rtc.disable32kHzOutput();                               // Disable 32kHz output, () → None

    bool stopped = rtc.oscillatorStopped();                 // Query Oscillator Stop Flag, () → bool
    rtc.clearOscillatorStopped();                            // Clear Oscillator Stop Flag, () → None

    rtc.enableBatteryOscillator();                          // Keep oscillator running on VBAT, () → None
    rtc.disableBatteryOscillator();                         // Stop oscillator on VBAT, () → None

    rtc.setAgingOffset(-5);                                 // Write oscillator trim code, (offset) → None
    int8_t aging = rtc.getAgingOffset();                    // Read oscillator trim code, () → int8_t

    printf("%04u-%02u-%02u  %.2f C  alarm1h=%u alarm2m=%u  status=0x%02X  32khz=%d  osf=%d  aging=%d\n",
           dt.year, dt.month, dt.day, (double)tempC, got1.hour, got2.minute, status, en32, stopped, aging);
}
