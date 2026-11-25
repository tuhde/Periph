#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "AD5248.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x2C
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    AD5248Full pot(connection);

    pot.reset();
    pot.set_wiper(0, 100);
    check_true("set/get wiper ch0", pot.get_wiper(0) == 100);
    pot.set_wiper(1, 200);
    check_true("set/get wiper ch1", pot.get_wiper(1) == 200);
    check_true("ch0 unchanged by ch1 write", pot.get_wiper(0) == 100);
    pot.set_resistance(0, 5320.0f);
    check_true("set_resistance midscale code", pot.get_wiper(0) == 128);
    check_true("get_resistance ~5320", pot.get_resistance(0) > 5319.0f && pot.get_resistance(0) < 5321.0f);
    pot.set_both(10, 20);
    check_true("set_both ch0", pot.get_wiper(0) == 10);
    check_true("set_both ch1", pot.get_wiper(1) == 20);
    pot.set_ratio(0, 0.5f);
    check_true("get_ratio ~0.5", pot.get_ratio(0) > 0.49f && pot.get_ratio(0) < 0.51f);
    pot.set_wiper(0, 250);
    check_true("increment clamps", pot.increment(0, 10) == 255);
    pot.set_wiper(1, 77);
    pot.shutdown(1);
    check_true("is_shutdown", pot.is_shutdown(1));
    pot.resume(1);
    check_true("RDAC kept across shutdown", pot.get_wiper(1) == 77);
    pot.reset();
    check_true("reset ch0", pot.get_wiper(0) == 0x80);
    check_true("reset ch1", pot.get_wiper(1) == 0x80);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
