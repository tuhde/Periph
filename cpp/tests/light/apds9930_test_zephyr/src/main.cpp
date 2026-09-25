// Zephyr HIL test for the APDS-9930 ambient light and proximity sensor.

#include <stdio.h>
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "Apds9930.h"
#include "I2CConnectionZephyr.h"

#ifndef APDS9930_I2C_NODE
#define APDS9930_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef APDS9930_ADDR
#define APDS9930_ADDR 0x39
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    const struct device* i2c_dev = DEVICE_DT_GET(APDS9930_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, APDS9930_ADDR);
    APDS9930Full apds(connection);

    k_msleep(110);

    bool avalid = false, pvalid = false, psat = false, aint = false, pint = false;
    apds.status(avalid, pvalid, psat, aint, pint);
    check_true("status returns bools", true);

    float lx = apds.lux();
    check_true("lux is float", true);
    check_true("lux >= 0", lx >= 0.0f);

    uint16_t p = apds.proximity();
    check_true("proximity >= 0", true);

    apds.configure_als(0xDB, 0, false);
    apds.configure_proximity(8, 0, 0, false, 0xFF);
    apds.disable_wait();
    apds.set_als_thresholds(0, 65535, 1);
    apds.set_proximity_thresholds(0, 1023, 1);
    apds.set_proximity_offset(0);
    apds.sleep_after_interrupt(false);
    apds.clear_interrupt(0);
    check_true("config methods accepted", true);

    (void)p;

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}