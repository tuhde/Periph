// ESP-IDF test for TMP117.
// Mirrors the Zephyr test for TMP117; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "TMP117.h"

static int passed = 0, failed = 0;

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
    dev_cfg.device_address = TMP117Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    TMP117Minimal sensor(connection);                       // Create TMP117 driver, (connection)
    TMP117Full full(connection);                            // Create TMP117 Full driver, (connection)
    full.configure(TMP117Full::Mode::Continuous, 8, 0.125f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    vTaskDelay(pdMS_TO_TICKS(300));
    float t = sensor.readTemperature();                     // Read temperature, () → °C
    check_true(t >= -40.0f && t <= 125.0f, "temperature_plausible");

    full.configure(TMP117Full::Mode::Continuous, 32, 4.0f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    TMP117Full::Config cfg = full.getConfig();              // Read conversion config, () → Config
    check_true(cfg.mode == TMP117Full::Mode::Continuous && cfg.averaging == 32 && cfg.cycleSeconds == 4.0f,
               "config_roundtrip");
    full.configure(TMP117Full::Mode::Shutdown, 0, 0.0155f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    check_true(full.isShutdown(), "shutdown");
    full.triggerOneShot();                                  // Start one conversion, () → void
    vTaskDelay(pdMS_TO_TICKS(50));
    check_true(full.isDataReady(), "one_shot_data_ready");
    check_true(full.isShutdown(), "one_shot_returns_to_shutdown");
    full.configure();                                       // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    check_true(!full.isShutdown(), "continuous");

    full.setHighLimit(80.0f);                               // Set THIGH_LIMIT, (celsius °C) → void
    check_true(full.getHighLimit() == 80.0f, "high_limit_roundtrip");
    full.setLowLimit(-10.25f);                              // Set TLOW_LIMIT, (celsius °C) → void
    check_true(full.getLowLimit() == -10.25f, "low_limit_roundtrip");
    full.setTemperatureOffset(0.5f);                        // Set calibration offset, (celsius °C) → void
    check_true(full.getTemperatureOffset() == 0.5f, "offset_roundtrip");
    full.setTemperatureOffset(0.0f);                        // Set calibration offset, (celsius °C) → void

    check_true(!full.isEepromBusy(), "eeprom_not_busy");
    uint16_t eeprom2 = 0;
    full.readEepromScratch(2, eeprom2);                     // Read EEPROM scratch, (slot 1|2|3, value&) → bool
    full.writeEepromScratch(2, 0xA55A);                     // Write EEPROM scratch, (slot 2, value 16-bit) → bool
    uint16_t scratch = 0;
    check_true(full.readEepromScratch(2, scratch) && scratch == 0xA55A, "eeprom2_volatile_roundtrip");

    // One real EEPROM program cycle (the conformance-checked eeprom_write_ready
    // timing): rewrite EEPROM2's original value while unlocked, so the stored
    // power-on value is unchanged. Costs one EEPROM2 endurance cycle per run.
    full.unlockEeprom();                                    // Unlock EEPROM, () → void
    full.writeEepromScratch(2, eeprom2);                    // Write EEPROM scratch, (slot 2, value 16-bit) → bool
    int waitedMs = 0;
    while (full.isEepromBusy() && waitedMs < 50) {          // Check EEPROM busy, () → bool
        vTaskDelay(pdMS_TO_TICKS(10));
        waitedMs += 10;
    }
    full.lockEeprom();                                      // Lock EEPROM, () → void
    check_true(waitedMs < 50, "eeprom_write_ready");
    check_true(full.readEepromScratch(2, scratch) && scratch == eeprom2, "eeprom2_restored");

    // High limit below ambient forces HIGH_Alert on the next conversion; in
    // Alert mode the flag latches until CONFIGURATION is read.
    full.configureAlert();                                  // Configure ALERT, (mode=Alert, polarity=ActiveLow, pinFunction=Alert) → void
    full.configure(TMP117Full::Mode::Continuous, 0, 0.0155f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    full.setHighLimit(t - 20.0f);                           // Set THIGH_LIMIT, (celsius °C) → void
    vTaskDelay(pdMS_TO_TICKS(100));
    check_true((full.pollInterrupt() & TMP117Full::SOURCE_HIGH) != 0, "poll_interrupt_high");
    full.setHighLimit(80.0f);                               // Set THIGH_LIMIT, (celsius °C) → void
    vTaskDelay(pdMS_TO_TICKS(100));
    full.pollInterrupt();                                   // Read alert flags, () → uint8_t mask
    check_true((full.pollInterrupt() & TMP117Full::SOURCE_HIGH) == 0, "poll_interrupt_clear");

    // Soft reset reloads CONFIGURATION, the limits and the offset from EEPROM.
    full.reset();                                           // Software reset, () → void
    check_true(full.getConfig().mode == TMP117Full::Mode::Continuous, "reset_restores_config");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
