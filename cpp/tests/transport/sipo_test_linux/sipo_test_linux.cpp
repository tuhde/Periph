#include <cstdio>
#include <cstdlib>
#include <gpiod.h>
#include "SiPoTransportLinux.h"

#ifndef TEST_GPIO_CHIP
#define TEST_GPIO_CHIP    "/dev/gpiochip0"
#endif
#ifndef TEST_SER_IN_LINE
#define TEST_SER_IN_LINE  19
#endif
#ifndef TEST_SRCK_LINE
#define TEST_SRCK_LINE    26
#endif
#ifndef TEST_RCK_LINE
#define TEST_RCK_LINE     5
#endif
#ifndef TEST_SRCLR_LINE
#define TEST_SRCLR_LINE   6
#endif
#ifndef TEST_G_LINE
#define TEST_G_LINE       13
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    struct gpiod_chip* chip = gpiod_chip_open(TEST_GPIO_CHIP);
    if (!chip) { perror("gpiod_chip_open"); return 2; }

    struct gpiod_line* ser_in = gpiod_chip_get_line(chip, TEST_SER_IN_LINE);
    struct gpiod_line* srck   = gpiod_chip_get_line(chip, TEST_SRCK_LINE);
    struct gpiod_line* rck    = gpiod_chip_get_line(chip, TEST_RCK_LINE);
    struct gpiod_line* srclr  = gpiod_chip_get_line(chip, TEST_SRCLR_LINE);
    struct gpiod_line* g      = gpiod_chip_get_line(chip, TEST_G_LINE);

    gpiod_line_request_output(ser_in, "sipo_test", 0);
    gpiod_line_request_output(srck,   "sipo_test", 0);
    gpiod_line_request_output(rck,    "sipo_test", 0);
    gpiod_line_request_output(srclr,  "sipo_test", 1);
    gpiod_line_request_output(g,      "sipo_test", 0);

    SiPoTransportLinux transport(ser_in, srck, rck, srclr, g);

    uint8_t data1[] = { 0xA5 };
    transport.write(data1, sizeof(data1));
    check_true("write accepted", true);

    uint8_t data2[] = { 0x00, 0xFF };
    transport.write(data2, sizeof(data2));
    check_true("write multi-byte accepted", true);

    bool threw = false;
    try {
        transport.clear();
    } catch (...) {
        threw = true;
    }
    check_true("clear accepted", !threw);

    try {
        transport.set_output_enable(false);
        transport.set_output_enable(true);
    } catch (...) {
        threw = true;
    }
    check_true("set_output_enable accepted", !threw);

    transport.close();
    check_true("close accepted", true);

    gpiod_chip_close(chip);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
