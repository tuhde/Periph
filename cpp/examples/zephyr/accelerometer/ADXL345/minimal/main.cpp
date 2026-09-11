#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "ADXL345.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define ADXL345_ADDR 0x53

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, ADXL345_ADDR);
    ADXL345Minimal accel(connection);                       // Create ADXL345 driver, (connection, spi=false)

    while (1) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        printk("x=%.3f y=%.3f z=%.3f g\n", (double)x, (double)y, (double)z);
        k_sleep(K_MSEC(100));
    }
    return 0;
}