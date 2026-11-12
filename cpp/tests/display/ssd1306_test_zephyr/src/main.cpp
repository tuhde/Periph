#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "SSD1306.h"
#include "ssd1306_smoke.h"

#ifndef SSD1306_I2C_NODE
#define SSD1306_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef SSD1306_ADDR
#define SSD1306_ADDR 0x3C
#endif

static int passed = 0;
static int failed = 0;

static void report(const char* label, bool ok) {
    if (ok) { printk("PASS %s\n", label); passed++; }
    else    { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(SSD1306_I2C_NODE);
    I2CConnectionZephyr conn(dev, SSD1306_ADDR);
    static SSD1306Full chip(conn);
    ssd1306Checks(chip, report);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return 0;
}
