#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "SH1106.h"
#include "sh1106_smoke.h"

#ifndef SH1106_I2C_NODE
#define SH1106_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef SH1106_ADDR
#define SH1106_ADDR 0x3C
#endif

static int passed = 0;
static int failed = 0;

static void report(const char* label, bool ok) {
    if (ok) { printk("PASS %s\n", label); passed++; }
    else    { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(SH1106_I2C_NODE);
    I2CConnectionZephyr conn(dev, SH1106_ADDR);
    static SH1106Full chip(conn);
    sh1106Checks(chip, report);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return 0;
}
