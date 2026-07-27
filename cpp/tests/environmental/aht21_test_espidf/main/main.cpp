// Auto-generated ESP-IDF test for AHT21.
// Mirrors the Zephyr test for AHT21; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AHT21.h"

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
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x38,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AHT21Full inst(connection);  // Create AHT21 driver
    check_true(inst.is_calibrated(), "is_calibrated");
    check_true(!inst.is_busy(), "not busy at idle");
    float t, h;
    inst.read(t, h);
    check_near(t, -40.0f, 120.0f, "temperature range");
    check_near(h, 0.0f, 100.0f, "humidity range");
    float tr = inst.temperature();
    check_near(tr, -40.0f, 120.0f, "read_temperature range");
    float hr = inst.humidity();
    check_near(hr, 0.0f, 100.0f, "read_humidity range");
    float tc, hc;
    bool crc_ok = inst.read_with_crc(tc, hc);
    check_true(crc_ok, "crc_ok");
    check_near(tc, -40.0f, 120.0f, "crc temperature range");
    check_near(hc, 0.0f, 100.0f, "crc humidity range");
    inst.soft_reset();
    vTaskDelay(pdMS_TO_TICKS(50));
    check_true(inst.is_calibrated(), "calibrated after reset");
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
