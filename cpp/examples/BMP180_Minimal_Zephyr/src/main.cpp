#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "BMP180.h"

#ifndef BMP180_I2C_NODE
#define BMP180_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP180_ADDR
#define BMP180_ADDR 0x77
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP180_I2C_NODE);
    I2CTransportZephyr transport(dev, BMP180_ADDR);
    BMP180Minimal bmp(transport);                      // Create BMP180 driver, (transport)

    for (int i = 0; i < 5; i++) {
        float t = bmp.temperature();                   // Read temperature, () → float C
        float p = bmp.pressure();                     // Read pressure, () → float hPa
        printk("%.1f C, %.1f hPa\n", t, p);
        k_sleep(K_SECONDS(1));
    }

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
