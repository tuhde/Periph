#include <cstdio>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "INA219.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x40
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);
    INA219Full ina(transport);

    check_true("voltage non-negative",     ina.voltage()       >= 0.0f);
    check_true("shunt_voltage finite",      ina.shunt_voltage() > -1.0f);
    check_true("current finite",            ina.current()       > -10.0f);
    check_true("power non-negative",       ina.power()         >= 0.0f);

    check_true("conversion_ready", ina.conversion_ready());
    check_true("no overflow",      !ina.overflow());

    ina.configure(1, 3, 0x03, 0x03, 7);
    check_true("after configure: voltage non-negative", ina.voltage() >= 0.0f);

    ina.shutdown();
    usleep(1000);
    ina.wake();
    check_true("wake: voltage non-negative", ina.voltage() >= 0.0f);

    ina.reset();
    check_true("after reset: voltage non-negative", ina.voltage() >= 0.0f);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}