#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "UC1611s.h"
#include "uc1611s_smoke.h"

#ifndef UC1611S_I2C_NODE
#define UC1611S_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef UC1611S_ADDR
#define UC1611S_ADDR 0x38
#endif

static int passed = 0;
static int failed = 0;

static void report(const char* label, bool ok) {
    if (ok) { printk("PASS %s\n", label); passed++; }
    else    { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(UC1611S_I2C_NODE);
    I2CConnectionZephyr cmdConn(dev, UC1611S_ADDR);
    I2CConnectionZephyr dataConn(dev, UC1611S_ADDR + 1);
    static UC1611sFull chip(cmdConn, dataConn);
    uc1611sChecks(chip, report);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return 0;
}
