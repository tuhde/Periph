#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "ADE7953.h"

#ifndef ADE7953_I2C_NODE
#define ADE7953_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef ADE7953_ADDR
#define ADE7953_ADDR 0x38
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool cond) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else      { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(ADE7953_I2C_NODE);
    I2CConnectionZephyr connection(dev, ADE7953_ADDR);
    ADE7953Full ade(connection, 251.0f, 30.0f);

    check_true("voltage non-negative", ade.voltage() >= 0.0f);
    check_true("current non-negative", ade.current() >= 0.0f);
    check_true("activePower finite",   ade.activePower() > -1.0e6f);
    check_true("activeEnergy finite",  ade.activeEnergy() > -1000.0f);
    check_true("linePeriod positive",  ade.linePeriod() > 0.0f);

    ade.reset();
    check_true("voltage after reset", ade.voltage() >= 0.0f);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}