// ESP-IDF test for Isl29125.
// Mirrors the Zephyr test for Isl29125; prints PASS/FAIL and exits.

#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Isl29125.h"

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
    dev_cfg.device_address = Isl29125Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Isl29125Minimal sensor(connection);                    // Create Isl29125 driver, (connection)
    Isl29125Full full(connection);                          // Create Isl29125 Full driver, (connection)

    Isl29125Counts c = {0, 0, 0};
    check_true(sensor.valid(), "valid");
    check_true(sensor.read(c), "read");                    // Read the three channels, (counts&) → bool
    check_true((uint32_t)c.red + c.green + c.blue < 3u * 65536u, "read_range");

    check_true(full.deviceId() == 0x7D, "device_id");       // Read DEVICE_ID, () → uint8_t
    check_true(full.setRange(375) && full.setRange(10000), "set_range");
    check_true(full.setResolution(12) && full.setResolution(16), "set_resolution");
    check_true(!full.setRange(100) && !full.setResolution(8), "invalid_config");
    check_true(full.setIrCompensation(true, 0x3F), "ir_compensation");
    full.setSync(false);
    full.readRgb(c);
    check_true((uint32_t)c.red + c.green + c.blue < 3u * 65536u, "read_rgb_range");
    check_true((uint32_t)full.readGreen() + full.readRed() + full.readBlue() < 3u * 65536u, "read_single");
    check_true(full.setMode(Isl29125Full::MODE_GREEN), "mode_green");
    check_true((uint32_t)full.readGreen() + full.readRed() < 2u * 65536u, "green_mode_reads");
    full.setMode(Isl29125Full::MODE_RGB);
    check_true(full.setThresholds(100, 60000), "set_thresholds");
    uint16_t lo = 0, hi = 0;
    full.getThresholds(lo, hi);
    check_true(lo == 100 && hi == 60000, "thresholds_roundtrip");
    check_true(full.setPersistence(2) && full.setInterruptChannel(Isl29125Full::CHANNEL_GREEN), "interrupt_config");
    full.enableInterrupt(Isl29125Full::SOURCE_THRESHOLD);
    full.disableInterrupt(Isl29125Full::SOURCE_THRESHOLD);
    full.status();
    check_true((full.pollInterrupt() & Isl29125Full::STATUS_THRESHOLD) == 0, "status_clean");
    full.setMode(Isl29125Full::MODE_POWER_DOWN);
    full.softReset();
    full.setMode(Isl29125Full::MODE_RGB);
    check_true(full.deviceId() == 0x7D, "soft_reset_id");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
