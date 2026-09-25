#include <math.h>
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "L3gd20h.h"

#ifndef L3GD20H_I2C_NODE
#define L3GD20H_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef L3GD20H_ADDR
#define L3GD20H_ADDR 0x6A
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(L3GD20H_I2C_NODE);
    I2CConnectionZephyr connection(dev, L3GD20H_ADDR);
    L3gd20hMinimal gyro(connection);

    float x, y, z;
    gyro.gyro(x, y, z);
    check_true(!isnan(x) && !isnan(y) && !isnan(z), "gyro() returns valid floats");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return 0;
}
