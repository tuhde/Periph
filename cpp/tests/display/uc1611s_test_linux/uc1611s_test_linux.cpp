#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x38
#endif

#include <stdio.h>
#include "I2CConnectionLinux.h"
#include "UC1611s.h"
#include "../uc1611s_smoke.h"

static int passed = 0;
static int failed = 0;

static void report(const char* label, bool ok) {
    if (ok) { printf("PASS %s\n", label); passed++; }
    else    { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux cmdConn(TEST_I2C_BUS, TEST_ADDR);
    I2CConnectionLinux dataConn(TEST_I2C_BUS, TEST_ADDR + 1);
    static UC1611sFull chip(cmdConn, dataConn);
    uc1611sChecks(chip, report);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
