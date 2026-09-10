#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "BMP581.h"

#ifndef BMP581_I2C_NODE
#define BMP581_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP581_ADDR
#define BMP581_ADDR 0x46
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP581_I2C_NODE);
    I2CConnectionZephyr connection(dev, BMP581_ADDR);
    BMP581Minimal bmp(connection);                        // Create BMP581 driver, (connection, spi=false)

    for (int i = 0; i < 5; i++) {
        float p = bmp.pressure();                         // Read pressure, () → float Pa
        float t = bmp.temperature();                      // Read temperature, () → float °C
        printk("%.1f C, %.1f Pa\n", t, p);
        k_sleep(K_SECONDS(1));
    }

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}