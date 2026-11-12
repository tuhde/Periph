#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x3C
#endif

#include <stdio.h>
#include "I2CConnectionLinux.h"
#include "SSD1305.h"
#include "../ssd1305_smoke.h"

static int passed = 0;
static int failed = 0;

static void report(const char* label, bool ok) {
    if (ok) { printf("PASS %s\n", label); passed++; }
    else    { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux conn(TEST_I2C_BUS, TEST_ADDR);
    static SSD1305Full chip(conn);
    ssd1305Checks(chip, report);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
