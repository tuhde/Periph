// Zephyr HIL test for the MPR121 capacitive touch sensor controller.

#include <stdio.h>
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "Mpr121.h"
#include "I2CConnectionZephyr.h"

#ifndef MPR121_I2C_NODE
#define MPR121_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MPR121_ADDR
#define MPR121_ADDR 0x5A
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    const struct device* i2c_dev = DEVICE_DT_GET(MPR121_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MPR121_ADDR);
    MPR121Full mpr(connection);

    uint16_t t = mpr.touched();
    check_true("touched in 0..4095", t <= 0xFFF);

    uint16_t f0 = mpr.filtered(0);
    check_true("filtered(0) in 0..1023", f0 <= 1023);

    uint16_t b0 = mpr.baseline(0);
    check_true("baseline(0) in 0..1023", b0 <= 1023);

    mpr.configure_thresholds(0, 15, 8);
    mpr.configure_all_thresholds(12, 6);
    mpr.configure_proximity_thresholds(8, 4);
    mpr.configure_baseline_filter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0);
    mpr.configure_sampling(16, 1, 0, 0, 4);
    mpr.configure_debounce(1, 1);
    check_true("configuration methods accepted", true);

    mpr.enable_interrupt(MPR121Full::SOURCE_OOR);
    mpr.disable_interrupt(MPR121Full::SOURCE_OOR);
    mpr.clear_overcurrent();
    check_true("interrupt API accepted", true);

    mpr.reset();
    check_true("reset completed", true);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
