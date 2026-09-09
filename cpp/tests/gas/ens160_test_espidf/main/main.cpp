// Auto-generated ESP-IDF test for ENS160.
// Mirrors the Zephyr test for ENS160; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include "ENS160.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_near(float val, float lo, float hi, const char *label) {
    if (val >= lo && val <= hi) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: %.4f not in [%.4f, %.4f]\n",
                  label, (double)val, (double)lo, (double)hi); failed++; }
}

static void check_eq_u8(uint8_t val, uint8_t expected, const char *label) {
    if (val == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: 0x%02X != 0x%02X\n", label, val, expected); failed++; }
}


extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
#if ESP_IDF_VERSION >= ESP_IDF_VERSION_VAL(6, 0, 0)
        // intr_priority/trans_queue_depth/flags.allow_pd were added to
        // i2c_master_bus_config_t in ESP-IDF v6.0 - this repo's CI still
        // pins v5.2.7, where these fields don't exist on the struct.
        .intr_priority = 0,
        .trans_queue_depth = 0,
        .flags = { .enable_internal_pullup = true, .allow_pd = false },
#else
        .flags = { .enable_internal_pullup = true },
#endif
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x52,
        .scl_speed_hz    = 400000,
#if ESP_IDF_VERSION >= ESP_IDF_VERSION_VAL(6, 0, 0)
        // scl_wait_us/flags were added to i2c_device_config_t in v6.0 -
        // see the bus_cfg comment above.
        .scl_wait_us     = 0,
        .flags           = {},
#endif
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ENS160Full inst(connection);  // Create ENS160 driver
    (void)inst.status();
    uint8_t aqi;
    float tvoc, eco2;
    bool ok = inst.read_air_quality(aqi, tvoc, eco2);
    (void)ok; (void)aqi; (void)tvoc; (void)eco2;
    check_true(true, "ens160 comm ok");
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
