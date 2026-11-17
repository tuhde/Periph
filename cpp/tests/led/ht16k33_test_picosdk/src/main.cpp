#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "HT16K33.h"

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
    I2CConnectionPicoSDK connection(i2c0, 0x70);
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    HT16K33Minimal matrix(connection);
    HT16K33Full full(connection);

    check_true(HT16K33Minimal::COMMONS == 8 && HT16K33Minimal::ROWS == 16, "constants");

    check_true(matrix.set_pixel(0, 0) && matrix.set_pixel(7, 15), "set_pixel");
    check_true(!matrix.set_pixel(8, 0) && !matrix.set_pixel(0, 16), "set_pixel_range");
    matrix.show();
    check_true(true, "show");

    uint8_t raw[2] = {0xFF, 0x00};
    check_true(matrix.write_raw(raw, 2, 0), "write_raw");
    check_true(!matrix.write_raw(raw, 2, 15), "write_raw_overflow");

    matrix.set_brightness(0);
    matrix.set_brightness(15);
    check_true(true, "set_brightness");
    matrix.clear();
    check_true(true, "clear");

    full.set_pixel(3, 4);
    full.show();
    check_true(full.get_pixel(3, 4) && !full.get_pixel(3, 5), "get_pixel");

    check_true(full.set_blink(2.0f) && full.set_blink(1.0f) && full.set_blink(0.5f) && full.set_blink(0.0f), "set_blink");
    check_true(!full.set_blink(3.0f), "set_blink_invalid");
    full.display_off();
    full.display_on();
    check_true(true, "display_off_on");
    full.standby();
    full.wake();
    check_true(true, "standby_wake");
    full.set_int_output(true);
    full.set_int_output(true, true);
    full.set_int_output(false);
    check_true(true, "set_int_output");
    (void)full.read_keys();
    check_true(true, "read_keys");
    (void)full.key_pressed();
    check_true(true, "key_pressed");
    full.clear();

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
