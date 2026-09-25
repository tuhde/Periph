// ESP-IDF test for MCP9808.
// Mirrors the Zephyr test for MCP9808; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MCP9808.h"

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
    dev_cfg.device_address = MCP9808Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    MCP9808Minimal sensor(connection);                      // Create MCP9808 driver, (connection)
    float t = sensor.readTemperature();                     // Read ambient temperature, () → °C
    check_true(t >= -40.0f && t <= 125.0f, "temperature_plausible");

    MCP9808Full full(connection);                           // Create MCP9808 Full driver, (connection)
    full.setResolution(0.5f);                               // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → bool
    check_true(full.getResolution() == 0.5f, "resolution_0_5");
    full.setResolution(0.0625f);                            // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → bool
    check_true(full.getResolution() == 0.0625f, "resolution_0_0625");

    full.setUpperLimit(80.0f);                              // Set TUPPER, (celsius °C) → void
    check_true(full.getUpperLimit() == 80.0f, "upper_limit_roundtrip");
    full.setLowerLimit(-10.25f);                            // Set TLOWER, (celsius °C) → void
    check_true(full.getLowerLimit() == -10.25f, "lower_limit_roundtrip");
    full.setCriticalLimit(100.0f);                          // Set TCRIT, (celsius °C) → void
    check_true(full.getCriticalLimit() == 100.0f, "critical_limit_roundtrip");

    full.setHysteresis(1.5f);                               // Set hysteresis, (celsius 0|1.5|3.0|6.0) → bool
    check_true(full.getHysteresis() == 1.5f, "hysteresis_roundtrip");
    full.setHysteresis(0.0f);                               // Set hysteresis, (celsius 0|1.5|3.0|6.0) → bool

    full.shutdown();                                        // Enter Shutdown mode, () → void
    check_true(full.isShutdown(), "shutdown");
    full.wake();                                            // Leave Shutdown mode, () → void
    check_true(!full.isShutdown(), "wake");

    // Lower limit above ambient forces TA < TLOWER; the status bit is live
    // regardless of whether the Alert output is enabled.
    full.setLowerLimit(t + 20.0f);                          // Set TLOWER, (celsius °C) → void
    vTaskDelay(pdMS_TO_TICKS(300));
    check_true((full.pollInterrupt() & MCP9808Full::SOURCE_LOWER) != 0, "poll_interrupt_lower");
    full.setLowerLimit(-10.25f);                            // Set TLOWER, (celsius °C) → void
    vTaskDelay(pdMS_TO_TICKS(300));
    check_true((full.pollInterrupt() & MCP9808Full::SOURCE_LOWER) == 0, "poll_interrupt_clear");

    check_true(full.configureAlert(), "configure_alert");   // Configure Alert, (mode=All, output=Comparator, polarity=ActiveLow) → bool
    full.enableAlert();                                     // Enable Alert output, () → void
    check_true(!full.isAlertAsserted(), "alert_not_asserted_in_window");
    full.disableAlert();                                    // Disable Alert output, () → void
    full.clearInterrupt();                                  // Clear interrupt-mode Alert, () → void

    // The lock bits are one-way until power-on reset and are never set here.
    check_true(!full.isCriticalLimitLocked(), "not_critical_locked");
    check_true(!full.isWindowLimitsLocked(), "not_window_locked");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
