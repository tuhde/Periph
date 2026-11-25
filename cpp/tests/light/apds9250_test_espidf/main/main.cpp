// ESP-IDF test for Apds9250.
// Mirrors the Zephyr test for Apds9250; prints PASS/FAIL and exits.

#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Apds9250.h"

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
    dev_cfg.device_address = Apds9250Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Apds9250Minimal sensor(connection);                    // Create Apds9250 driver, (connection)
    Apds9250Full full(connection);                          // Create Apds9250 Full driver, (connection)

    Apds9250Counts c = {0, 0, 0, 0};
    check_true(sensor.valid(), "valid");
    check_true(sensor.read(c), "read");                    // Read the four channels, (counts&) → bool
    check_true(c.red <= 0xFFFFF && c.green <= 0xFFFFF && c.blue <= 0xFFFFF && c.ir <= 0xFFFFF, "read_range");

    uint8_t part = 0, rev = 0;
    full.partId(part, rev);                                 // Read PART_ID, (part&, revision&) → void
    check_true(part == 0xB, "part_id");
    check_true(full.getResolution() == 18 && full.getMeasurementRate() == 100 && full.getGain() == 3, "defaults");
    check_true(full.setGain(18) && full.getGain() == 18, "gain_18");
    full.setGain(3);
    check_true(full.setResolution(16) && full.getResolution() == 16, "resolution_16");
    check_true(fabsf(full.integrationTime() - 0.025f) < 1e-4f, "integration_time");
    full.setResolution(18);
    check_true(full.setMeasurementRate(200) && full.getMeasurementRate() == 200, "rate_200");
    full.setMeasurementRate(100);
    full.readChannels(c);
    check_true(c.green <= 0xFFFFF, "read_channels_range");
    check_true(full.readGreen() <= 0xFFFFF, "read_single_green");
    full.setMode(false);
    check_true(full.readIr() <= 0xFFFFF, "als_mode_reads");
    full.setMode(true);
    check_true(full.setThresholds(100, 200000), "set_thresholds");
    uint32_t lo = 0, hi = 0;
    full.getThresholds(lo, hi);
    check_true(lo == 100 && hi == 200000, "thresholds_roundtrip");
    check_true(full.setPersistence(3) && full.setVariance(64) && full.setInterruptChannel(Apds9250Full::CHANNEL_GREEN), "interrupt_config");
    full.enableInterrupt(Apds9250Full::SOURCE_VARIATION);
    full.disableInterrupt();
    full.status();
    check_true((full.pollInterrupt() & Apds9250Full::STATUS_INTERRUPT) == 0, "status_clean");
    full.disable();
    full.enable();
    full.softReset();
    check_true(full.getGain() == 3 && full.getResolution() == 18, "soft_reset_defaults");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
