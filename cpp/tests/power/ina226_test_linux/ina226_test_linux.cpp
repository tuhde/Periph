#include <cstdio>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "INA226.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x40
#endif

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint16_t got, uint16_t expected) {
    if (got == expected) {
        printf("PASS %s\n", label); passed++;
    } else {
        printf("FAIL %s: got 0x%04X, expected 0x%04X\n", label, got, expected); failed++;
    }
}

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);
    INA226Full ina(transport);

    check_eq("manufacturer_id", ina.manufacturer_id(), 0x5449);
    check_eq("die_id",          ina.die_id(),          0x2260);

    check_true("voltage non-negative",     ina.voltage()       >= 0.0f);
    check_true("shunt_voltage finite",     ina.shunt_voltage() > -1.0f);
    check_true("current finite",           ina.current()       > -10.0f);
    check_true("power non-negative",       ina.power()         >= 0.0f);

    check_true("conversion_ready", ina.conversion_ready());
    check_true("no overflow",      !ina.overflow());

    ina.configure(3, 4, 4, 7);
    check_eq("configure: mfr_id still valid", ina.manufacturer_id(), 0x5449);

    ina.set_alert(INA226Full::POL, 1.0f, false, true);
    check_true("set_alert POL: LEN bit set", (ina.alert_flags() & 0x0001) != 0);

    ina.shutdown();
    usleep(1000);
    ina.wake();
    check_true("wake: voltage non-negative", ina.voltage() >= 0.0f);

    ina.reset();
    check_eq("reset: mfr_id still valid", ina.manufacturer_id(), 0x5449);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
