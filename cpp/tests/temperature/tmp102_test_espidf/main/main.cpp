// ESP-IDF test for TMP102.
// Mirrors the Zephyr test for TMP102; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "TMP102.h"

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
    dev_cfg.device_address = TMP102Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    TMP102Minimal sensor(connection);                       // Create TMP102 driver, (connection)
    TMP102Full full(connection);                            // Create TMP102 Full driver, (connection)
    vTaskDelay(pdMS_TO_TICKS(50));
    float t = sensor.readTemperature();                     // Read temperature, () → °C
    check_true(t >= -40.0f && t <= 125.0f, "temperature_plausible");
    float rawT = (float)full.readRaw() * 0.0625f;           // Read raw count, () → int16_t
    check_true(rawT - t < 1.0f && t - rawT < 1.0f, "raw_matches_temperature");
    check_true((full.readConfig() & 0x6000) == 0x6000, "default_config");  // Read CONFIGURATION, () → uint16_t

    full.setConversionRate(1.0f);                           // Set conversion rate, (hz) → bool
    check_true(((full.readConfig() >> 6) & 3) == 1, "rate_roundtrip");
    full.setConversionRate(4.0f);                           // Set conversion rate, (hz) → bool

    full.setTHigh(40.0f);                                   // Set T_HIGH, (celsius °C) → void
    check_true(full.getTHigh() == 40.0f, "t_high_roundtrip");
    full.setTLow(-10.25f);                                  // Set T_LOW, (celsius °C) → void
    check_true(full.getTLow() == -10.25f, "t_low_roundtrip");
    full.setTHigh(80.0f);                                   // Set T_HIGH, (celsius °C) → void
    full.setTLow(75.0f);                                    // Set T_LOW, (celsius °C) → void

    full.setFaultQueue(4);                                  // Set fault queue, (count) → bool
    check_true(((full.readConfig() >> 11) & 3) == 2, "fault_queue");
    full.setFaultQueue(1);                                  // Set fault queue, (count) → bool
    full.setPolarity(true);                                 // Set ALERT polarity, (activeHigh) → void
    check_true((full.readConfig() & 0x0400) != 0, "polarity");
    full.setPolarity(false);                                // Set ALERT polarity, (activeHigh) → void
    full.setThermostatMode(true);                           // Set thermostat mode, (interrupt) → void
    check_true((full.readConfig() & 0x0200) != 0, "thermostat_mode");
    full.setThermostatMode(false);                          // Set thermostat mode, (interrupt) → void

    full.setExtendedMode(true);                             // Set extended mode, (on) → void
    vTaskDelay(pdMS_TO_TICKS(300));
    float ext = full.readTemperature();
    check_true(ext - t < 3.0f && t - ext < 3.0f, "extended_mode_reading");
    full.setExtendedMode(false);                            // Set extended mode, (on) → void

    full.shutdown();                                        // Shut down, () → void
    check_true((full.readConfig() & 0x0100) != 0, "shutdown");
    float t1 = 0.0f;
    bool ok = full.oneShot(t1);                             // One-shot reading, (celsius&) → bool
    check_true(ok && t1 >= -40.0f && t1 <= 125.0f, "one_shot_plausible");
    check_true((full.readConfig() & 0x0100) != 0, "one_shot_stays_shutdown");
    full.wake();                                            // Wake, () → void
    check_true((full.readConfig() & 0x0100) == 0, "wake");

    // T_HIGH below ambient forces an alert after the fault queue elapses.
    full.setThermostatMode(false);                          // Set thermostat mode, (interrupt) → void
    full.setTLow(t - 21.0f);                                // Set T_LOW, (celsius °C) → void
    full.setTHigh(t - 20.0f);                               // Set T_HIGH, (celsius °C) → void
    vTaskDelay(pdMS_TO_TICKS(500));
    check_true(full.alertState(), "alert_state_active");    // Read alert state, () → bool
    check_true(full.pollInterrupt(), "poll_interrupt_active");  // Poll the alert state, () → bool
    full.setTHigh(80.0f);                                   // Set T_HIGH, (celsius °C) → void
    full.setTLow(75.0f);                                    // Set T_LOW, (celsius °C) → void
    vTaskDelay(pdMS_TO_TICKS(500));
    check_true(!full.alertState(), "alert_state_clear");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
