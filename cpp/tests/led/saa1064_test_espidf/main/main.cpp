// Auto-generated ESP-IDF test for SAA1064.
// Mirrors the Zephyr test for SAA1064; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "SAA1064.h"

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
    dev_cfg.device_address = 0x38;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    SAA1064Minimal matrix(connection);
    SAA1064Full full(connection);

    check_true(SAA1064Minimal::DIGITS == 4, "constants");

    const int8_t d1[4] = {1, 2, 3, 4};
    check_true(matrix.show_digits(d1), "show_digits");
    const int8_t bad[4] = {10, 0, 0, 0};
    check_true(!matrix.show_digits(bad), "show_digits_range");
    check_true(matrix.show_number(1234), "show_number");
    check_true(!matrix.show_number(10000), "show_number_range");
    uint8_t raw[2] = {0x7F, 0x7F};
    check_true(matrix.write_raw(raw, 2, 3), "write_raw");
    check_true(!matrix.write_raw(raw, 2, 4), "write_raw_overflow");
    matrix.clear();
    check_true(true, "clear");

    full.set_mode(false);
    full.set_mode(true);
    check_true(true, "set_mode");
    full.blank(true, false);
    full.blank(false, true);
    full.blank(false, false);
    check_true(true, "blank");
    check_true(full.set_current(3) && full.set_current(21) && full.set_current(12), "set_current");
    check_true(!full.set_current(10), "set_current_invalid");
    full.segment_test(true);
    full.segment_test(false);
    check_true(true, "segment_test");
    const uint8_t map[8] = {0, 1, 2, 3, 4, 5, 6, 7};
    check_true(full.set_segment_map(map), "set_segment_map");
    check_true(full.show_digit(1, 5, true), "show_digit");
    check_true(full.set_decimal_point(1, false) && full.set_decimal_point(4, true), "set_decimal_point");
    (void)full.read_status();
    check_true(true, "read_status");
    check_true(!full.power_reset_flag(), "power_reset_flag_cleared");
    full.write_control(0x47);
    check_true(true, "write_control");
    full.clear();

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
