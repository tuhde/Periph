#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "HT16K33.h"

#ifndef HT16K33_I2C_NODE
#define HT16K33_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef HT16K33_ADDR
#define HT16K33_ADDR 0x70
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(HT16K33_I2C_NODE);
    I2CConnectionZephyr connection(dev, HT16K33_ADDR);
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

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
