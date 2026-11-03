#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "BMA180.h"

#ifndef BMA180_I2C_NODE
#define BMA180_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMA180_ADDR
#define BMA180_ADDR 0x40
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(BMA180_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, BMA180_ADDR);

    BMA180Minimal accel(connection);                       // Create BMA180 driver, (connection)

    float x, y, z;
    accel.read(x, y, z);                                   // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(x == x && y == y && z == z, "read_returns_floats");

    BMA180Full accel_full(connection);                    // Create BMA180 Full driver, (connection)
    accel_full.set_range(4);
    accel_full.read(x, y, z);                              // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(x == x && y == y && z == z, "read_after_set_range_4g");

    float temp = accel_full.read_temperature();            // Read temperature, () → °C
    check_true(temp >= -40.0f && temp <= 87.5f, "temperature_in_range");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}