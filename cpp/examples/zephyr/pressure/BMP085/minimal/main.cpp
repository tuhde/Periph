#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "BMP085.h"

#ifndef BMP085_I2C_NODE
#define BMP085_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP085_ADDR
#define BMP085_ADDR 0x77
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP085_I2C_NODE);
    I2CConnectionZephyr connection(dev, BMP085_ADDR);
    BMP085Minimal bmp(connection);                      // Create BMP085 driver, (connection)

    for (int i = 0; i < 5; i++) {
        float t = bmp.temperature();                   // Read temperature, () → float C
        float p = bmp.pressure();                     // Read pressure, () → float Pa
        printk("%.1f C, %.1f Pa\n", t, p);
        k_sleep(K_SECONDS(1));
    }

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}