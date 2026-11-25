// ESP-IDF test for AD5243.
// Mirrors the Zephyr test for AD5243; prints PASS/FAIL and exits.

#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AD5243.h"

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
    dev_cfg.device_address = AD5243Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AD5243Minimal pot(connection);                          // Create AD5243 driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω)
    pot.setWiper(0, 100);                                   // Set wiper position, (channel 0–1, code 0–255) → bool
    check_true(pot.getWiper(0) == 100, "wiper0_roundtrip"); // Read wiper position, (channel 0–1) → uint8_t
    pot.setWiper(1, 200);
    check_true(pot.getWiper(1) == 200, "wiper1_roundtrip");
    check_true(pot.getWiper(0) == 100, "wiper0_independent");
    pot.setWiper(0, 300);
    check_true(pot.getWiper(0) == 255, "wiper_clamps_255");
    pot.setResistance(0, 5000.0f);                          // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    check_true(fabsf(pot.getResistance(0) - 5000.0f) < 50.0f, "set_resistance"); // Read W–B resistance, (channel 0–1) → float Ω

    AD5243Full full(connection);                            // Create AD5243 Full driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω)
    full.setBoth(10, 250);                                  // Write both wipers, (code1 0–255, code2 0–255) → void
    check_true(full.getWiper(0) == 10 && full.getWiper(1) == 250, "set_both");
    full.setRatio(0, 0.5f);                                 // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → bool
    check_true(full.getWiper(0) == 128 && fabsf(full.getRatio(0) - 0.5f) < 0.01f, "ratio_half"); // Read divider ratio, (channel 0–1) → float
    full.setResistanceWa(1, 3000.0f);                       // Set W–A resistance, (channel 0–1, ohms Ω) → bool
    check_true(fabsf(full.getResistanceWa(1) - 3000.0f) < 50.0f, "set_resistance_wa"); // Read W–A resistance, (channel 0–1) → float Ω
    full.setWiper(0, 100);
    check_true(full.increment(0, 5) == 105 && full.getWiper(0) == 105, "increment"); // Step the wiper, (channel 0–1, steps=1) → uint8_t
    check_true(full.increment(0, -500) == 0, "increment_clamps");

    full.setWiper(1, 77);
    full.shutdown(1);                                       // Shut channel down, (channel 0–1) → void
    check_true(full.isShutdown(1), "is_shutdown");          // Query shutdown state, (channel 0–1) → bool
    check_true(full.getWiper(1) == 77, "shutdown_keeps_rdac");
    full.resume(1);                                         // Leave shutdown, (channel 0–1) → void
    check_true(!full.isShutdown(1) && full.getWiper(1) == 77, "resume");

    full.setBoth(1, 2);
    full.reset();                                           // Restore power-up preset, () → void
    check_true(full.getWiper(0) == 128 && full.getWiper(1) == 128, "reset_midscale");


    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
