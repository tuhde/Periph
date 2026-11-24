#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "SAA1064.h"

int passed = 0;
int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_near(float v, float lo, float hi, const char *label) {
    if (v >= lo && v <= hi) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: %.4f not in [%.4f, %.4f]\n", label, (double)v, (double)lo, (double)hi); failed++; }
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x38);
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
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
    return failed == 0 ? 0 : 1;
}
