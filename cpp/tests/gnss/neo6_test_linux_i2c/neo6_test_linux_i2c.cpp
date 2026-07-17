#include <cstdio>
#include <cmath>
#include "I2CTransportLinux.h"
#include "NEO6.h"

// Requires a NEO-6 module wired to I2C (DDC) with a clear sky view. Achieving
// an actual fix needs an outdoor antenna and can take up to ~26 s (cold
// start); this test only requires that well-typed values come back.
#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x42
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);
    NEO6Minimal gps(transport, NEO6BusType::I2c);

    check_true("fix() starts at 0", gps.fix() == 0);
    check_true("latitude() starts at NAN", std::isnan(gps.latitude()));

    for (int i = 0; i < 3000; i++) {
        gps.update();
    }

    check_true("fix() is a valid quality code", gps.fix() == 0 || gps.fix() == 1 || gps.fix() == 2);
    check_true("satellites() is a non-negative int", gps.satellites() >= 0);
    if (gps.fix() > 0) {
        check_true("latitude() in range once fixed", gps.latitude() >= -90.0f && gps.latitude() <= 90.0f);
        check_true("longitude() in range once fixed", gps.longitude() >= -180.0f && gps.longitude() <= 180.0f);
        check_true("altitude() is populated once fixed", !std::isnan(gps.altitude()));
    } else {
        printf("note: no fix acquired during the test window (needs sky view)\n");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
