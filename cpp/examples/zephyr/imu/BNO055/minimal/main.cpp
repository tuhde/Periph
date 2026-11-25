#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "BNO055.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define BNO055_ADDR 0x29

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, BNO055_ADDR);
    BNO055Minimal imu(connection);                                      // Create BNO055 driver, (connection) → void
    float h, r, p;

    while (true) {
        imu.euler(h, r, p);                                             // Read fused Euler angles, (heading, roll, pitch) → void rad
        printk("heading %.2f rad, roll %.2f, pitch %.2f\n", (double)(h), (double)(r), (double)(p));
        k_sleep(K_MSEC(100));
    }
    return 0;
}
