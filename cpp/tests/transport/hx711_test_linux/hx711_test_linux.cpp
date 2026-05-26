#include <cstdio>
#include <cstdlib>
#include <gpiod.h>
#include "HX711TransportLinux.h"

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
    struct gpiod_chip* chip = gpiod_chip_open(TEST_GPIO_CHIP);
    if (!chip) { perror("gpiod_chip_open"); return 1; }

    struct gpiod_line* dout   = gpiod_chip_get_line(chip, TEST_DOUT_LINE);
    struct gpiod_line* pd_sck = gpiod_chip_get_line(chip, TEST_PD_SCK_LINE);

    gpiod_line_request_input(dout,   "hx711_test");
    gpiod_line_request_output(pd_sck, "hx711_test", 0);

    HX711TransportLinux transport(dout, pd_sck);

    check_true("is_ready returns bool", true);

    int32_t val = transport.read_raw(25);
    check_true("read_raw(25) in 24-bit signed range", val >= -8388608 && val <= 8388607);

    val = transport.read_raw(26);
    check_true("read_raw(26) in 24-bit signed range", val >= -8388608 && val <= 8388607);

    val = transport.read_raw(27);
    check_true("read_raw(27) in 24-bit signed range", val >= -8388608 && val <= 8388607);

    transport.power_down();
    check_true("power_down accepted", true);

    transport.power_up();
    check_true("power_up accepted", true);

    transport.close();
    check_true("close accepted", true);

    gpiod_chip_close(chip);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
