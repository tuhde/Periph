// ESP-IDF test for MAX44009.
// Mirrors the Zephyr test for MAX44009; prints PASS/FAIL and exits.

#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MAX44009.h"

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
    dev_cfg.device_address = MAX44009Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    MAX44009Minimal sensor(connection);                    // Create MAX44009 driver, (connection)
    float lx = sensor.lux();                                // Read illuminance, () → float lux
    check_true(lx >= 0.0f && lx <= 188006.4f, "lux_in_range");

    MAX44009Full full(connection);                          // Create MAX44009 Full driver, (connection)
    uint8_t e = 0, m = 0;
    bool over = full.readRaw(e, m);                         // Read exponent and mantissa, (exponent&, mantissa&) → bool overrange
    check_true(e <= 15 && over == (e == 15), "read_raw_ranges");
    full.setContinuous(true);                               // Select measurement mode, (continuous bool) → void
    check_true(full.getConfig().continuous, "continuous_on"); // Read CONFIG, () → Config
    full.setContinuous(false);
    check_true(!full.getConfig().continuous, "continuous_off");
    full.setManual(100.0f, false);                          // Select manual range, (integrationMs, divideBy8=false) → void
    MAX44009Full::Config cfg = full.getConfig();
    check_true(cfg.manual && fabsf(cfg.integrationMs - 100.0f) < 0.01f && !cfg.divideBy8, "manual_100ms");
    full.setManual(25.0f, true);
    cfg = full.getConfig();
    check_true(fabsf(cfg.integrationMs - 25.0f) < 0.01f && cfg.divideBy8, "manual_25ms_divide");
    full.setAutomatic();                                    // Select automatic range, () → void
    check_true(!full.getConfig().manual, "automatic");
    full.setThresholds(50.0f, 500.0f);                      // Set the interrupt window, (lowLux, highLux) → bool
    float lo = 0, hi = 0;
    full.getThresholds(lo, hi);                             // Read the window, (lowLux&, highLux&) → void
    check_true(fabsf(lo - 50.0f) / 50.0f < 0.2f && fabsf(hi - 500.0f) / 500.0f < 0.2f, "thresholds_roundtrip");
    full.setThresholdDelay(2.0f);                           // Set the threshold delay, (seconds) → void
    check_true(fabsf(full.getThresholdDelay() - 2.0f) < 0.05f, "threshold_delay");
    full.enableInterrupt();                                 // Enable the interrupt, () → void
    full.disableInterrupt();                                // Disable the interrupt, () → void
    check_true(full.pollInterrupt() == 0, "poll_interrupt_cleared"); // Read and clear INT_STATUS, () → uint8_t

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
