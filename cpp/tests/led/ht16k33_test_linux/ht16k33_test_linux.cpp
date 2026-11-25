#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x70
#endif

#include <stdio.h>
#include "I2CConnectionLinux.h"
#include "HT16K33.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
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
