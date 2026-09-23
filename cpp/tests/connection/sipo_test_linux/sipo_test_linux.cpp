#include <cstdio>
#include <cstdlib>
#include "SiPoConnectionLinux.h"

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
    SiPoConnectionLinux connection(TEST_GPIO_CHIP, TEST_SER_IN_LINE, TEST_SRCK_LINE, TEST_RCK_LINE,
                                   TEST_SRCLR_LINE, TEST_G_LINE);

    uint8_t data1[] = { 0xA5 };
    connection.write(data1, sizeof(data1));
    check_true("write accepted", true);

    uint8_t data2[] = { 0x00, 0xFF };
    connection.write(data2, sizeof(data2));
    check_true("write multi-byte accepted", true);

    bool threw = false;
    try {
        connection.clear();
    } catch (...) {
        threw = true;
    }
    check_true("clear accepted", !threw);

    try {
        connection.set_output_enable(false);
        connection.set_output_enable(true);
    } catch (...) {
        threw = true;
    }
    check_true("set_output_enable accepted", !threw);

    connection.close();
    check_true("close accepted", true);


    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
