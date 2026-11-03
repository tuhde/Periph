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

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(BMA180_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, BMA180_ADDR);
    BMA180Minimal accel(connection);                       // Create BMA180 driver, (connection)

    while (1) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        printk("x=%.3f y=%.3f z=%.3f g\n", (double)x, (double)y, (double)z);
        k_sleep(K_MSEC(100));
    }
    return 0;
}