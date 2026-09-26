#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
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
    I2CConnectionZephyr connection(dev, BMP180_ADDR);
    BMP180Minimal bmp(connection);

    float t = bmp.temperature();
    check_true(t >= -40.0f && t <= 85.0f, "temperature range");
    float p = bmp.pressure();
    check_true(p >= 30000.0f && p <= 110000.0f, "pressure range");

    BMP180Full bmp_full(connection, 0);
    check_true(bmp_full.oversampling() == 0, "default_oss");
    bmp_full.set_oversampling(2);
    check_true(bmp_full.oversampling() == 2, "set_oss");

    float alt = bmp_full.altitude();
    check_true(alt >= 0.0f, "altitude");
    float slp = bmp_full.sea_level_pressure(0.0f);
    check_true(slp >= 900.0f && slp <= 1100.0f, "sea_level_pressure");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
