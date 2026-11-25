#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "BMA150.h"

#ifndef BMA150_I2C_NODE
#define BMA150_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMA150_ADDR
#define BMA150_ADDR 0x38
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMA150_I2C_NODE);
    I2CConnectionZephyr connection(dev, BMA150_ADDR);
    BMA150Minimal accel(connection);                       // Create BMA150 driver, (connection)
    BMA150Full accel_full(connection);                     // Create BMA150 Full driver, (connection)

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(x == x && y == y && z == z, "read_returns_floats");

    accel_full.set_range(4);
    accel_full.read(x, y, z);
    check_true(x == x && y == y && z == z, "read_after_set_range_4g");

    float temp = accel_full.read_temperature();
    check_true(temp >= -30.0f && temp <= 97.5f, "temperature_in_range");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
