// ESP-IDF test for CAT5171.
// Mirrors the Zephyr test for CAT5171; prints PASS/FAIL and exits.

#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "CAT5171.h"

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
    dev_cfg.device_address = CAT5171Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    CAT5171Minimal pot(connection);                         // Create CAT5171 driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω)
    pot.setWiper(100);                                      // Set wiper position, (code 0–255) → void
    check_true(pot.getWiper() == 100, "wiper_roundtrip");   // Read wiper position, () → uint8_t
    pot.setWiper(300);
    check_true(pot.getWiper() == 255, "wiper_clamps_255");
    pot.setResistance(50000.0f);                            // Set W–B resistance, (ohms Ω) → void
    check_true(fabsf(pot.getResistance() - 50000.0f) < 500.0f, "set_resistance"); // Read W–B resistance, () → float Ω

    CAT5171Full full(connection);                           // Create CAT5171 Full driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω)
    full.setRatio(0.5f);                                    // Set divider ratio, (ratio 0.0–1.0) → void
    check_true(full.getWiper() == 128 && fabsf(full.getRatio() - 0.5f) < 0.01f, "ratio_half"); // Read divider ratio, () → float
    full.setResistanceWa(30000.0f);                         // Set W–A resistance, (ohms Ω) → void
    check_true(fabsf(full.getResistanceWa() - 30000.0f) < 500.0f, "set_resistance_wa"); // Read W–A resistance, () → float Ω
    full.setWiper(100);
    check_true(full.increment(5) == 105 && full.getWiper() == 105, "increment"); // Step the wiper, (steps=1) → uint8_t
    check_true(full.increment(-500) == 0, "increment_clamps");

    full.setWiper(77);
    full.shutdown();                                        // Shut down, () → void
    check_true(full.isShutdown(), "is_shutdown");           // Query shutdown state, () → bool
    check_true(full.getWiper() == 77, "shutdown_keeps_wcr");
    full.resume();                                          // Leave shutdown, () → void
    check_true(!full.isShutdown() && full.getWiper() == 77, "resume");

    full.setWiper(10);
    full.midscale();                                        // Move wiper to midscale, () → void
    check_true(full.getWiper() == 128, "midscale");

    full.setWiper(1);
    full.reset();                                           // Midscale reset, () → void
    check_true(full.getWiper() == 128, "reset_midscale");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
