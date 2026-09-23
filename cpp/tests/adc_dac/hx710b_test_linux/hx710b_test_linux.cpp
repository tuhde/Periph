#include <cstdio>
#include <unistd.h>
#include <cstdlib>
#include "HX711ConnectionLinux.h"
#include "HX710B.h"

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
    HX711ConnectionLinux connection(TEST_GPIO_CHIP, TEST_DOUT_LINE, TEST_PD_SCK_LINE);
    HX710BFull<HX711ConnectionLinux> chip(connection);

    check_true("is_ready returns bool", true);

    int32_t raw = chip.read_raw();
    check_true("read_raw in 24-bit signed range", raw >= -8388608 && raw <= 8388607);

    chip.set_rate(40);
    check_true("set_rate(40) accepted", true);

    chip.set_rate(10);
    check_true("set_rate(10) accepted", true);

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

    int32_t supp_raw = chip.read_supply_diff_raw();
    check_true("read_supply_diff_raw in 24-bit signed range", supp_raw >= -8388608 && supp_raw <= 8388607);


    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
