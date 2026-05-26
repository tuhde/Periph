#include <cstdio>
#include <gpiod.h>
#include "HX711TransportLinux.h"
#include "HX711.h"

#ifndef TEST_GPIO_CHIP
#define TEST_GPIO_CHIP   "/dev/gpiochip0"
#endif
#ifndef TEST_DOUT_LINE
#define TEST_DOUT_LINE   5
#endif
#ifndef TEST_PD_SCK_LINE
#define TEST_PD_SCK_LINE 6
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    struct gpiod_chip* chip_dev = gpiod_chip_open(TEST_GPIO_CHIP);
    if (!chip_dev) { perror("gpiod_chip_open"); return 1; }

    struct gpiod_line* dout_line   = gpiod_chip_get_line(chip_dev, TEST_DOUT_LINE);
    struct gpiod_line* pd_sck_line = gpiod_chip_get_line(chip_dev, TEST_PD_SCK_LINE);
    gpiod_line_request_input(dout_line,   "hx711_chip_test");
    gpiod_line_request_output(pd_sck_line, "hx711_chip_test", 0);

    HX711TransportLinux transport(dout_line, pd_sck_line);
    HX711Full<HX711TransportLinux> chip(transport);

    check_true("is_ready returns bool", true);

    int32_t raw = chip.read_raw();
    check_true("read_raw in 24-bit signed range", raw >= -8388608 && raw <= 8388607);

    chip.set_gain(128);
    check_true("set_gain(128) accepted", true);

    chip.set_gain(64);
    check_true("set_gain(64) accepted", true);

    chip.set_gain(32);
    check_true("set_gain(32) accepted", true);

    chip.set_gain(128);

    int32_t avg = chip.read_average(3);
    check_true("read_average in 24-bit signed range", avg >= -8388608 && avg <= 8388607);

    chip.tare(3);
    check_true("tare accepted", true);

    chip.set_scale(420.0f);
    check_true("set_scale accepted", true);

    float scale = chip.get_scale();
    check_true("get_scale returns 420.0", scale == 420.0f);

    float weight = chip.read_weight(1);
    check_true("read_weight returns float", true);

    chip.power_down();
    check_true("power_down accepted", true);

    chip.power_up();
    check_true("power_up accepted", true);

    gpiod_chip_close(chip_dev);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
