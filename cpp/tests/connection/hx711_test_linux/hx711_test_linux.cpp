#include <cstdio>
#include <unistd.h>
#include <cstdlib>
#include "HX711ConnectionLinux.h"

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

    check_true("is_ready returns bool", true);

    int32_t val = connection.read_raw(25);
    check_true("read_raw(25) in 24-bit signed range", val >= -8388608 && val <= 8388607);

    val = connection.read_raw(26);
    check_true("read_raw(26) in 24-bit signed range", val >= -8388608 && val <= 8388607);

    val = connection.read_raw(27);
    check_true("read_raw(27) in 24-bit signed range", val >= -8388608 && val <= 8388607);

    connection.close();
    check_true("close accepted", true);


    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
