#include <cstdio>
#include <cstdint>
#include "I2CConnectionLinux.h"
#include "ADE7953.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x38
#endif

static const float VOLTAGE_GAIN = 251.0f;
static const float CURRENT_GAIN = 30.0f;

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool cond) {
    if (cond) { std::printf("PASS %s\n", label); passed++; }
    else      { std::printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux conn(TEST_I2C_BUS, TEST_ADDR);
    ADE7953Full ade(conn, VOLTAGE_GAIN, CURRENT_GAIN);

    check_true("voltage non-negative", ade.voltage() >= 0.0f);
    check_true("current non-negative", ade.current() >= 0.0f);
    check_true("activePower finite",   ade.activePower() > -1.0e6f);
    check_true("activeEnergy finite",  ade.activeEnergy() > -1000.0f);

    check_true("linePeriod positive",  ade.linePeriod() > 0.0f);

    ade.reset();
    check_true("voltage after reset", ade.voltage() >= 0.0f);

    std::printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}