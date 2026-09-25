#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/i2c.h>
#include <I2CConnectionZephyr.h>
#include <L3gd20h.h>
#include <stdio.h>

#ifndef L3GD20H_I2C_NODE
#define L3GD20H_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef L3GD20H_ADDR
#define L3GD20H_ADDR 0x6A
#endif

int main(void) {
    const struct device* i2c_dev = DEVICE_DT_GET(L3GD20H_I2C_NODE);
    if (!device_is_ready(i2c_dev)) {
        printf("I2C device not ready\n");
        return 0;
    }

    I2CConnectionZephyr conn(i2c_dev, L3GD20H_ADDR);
    L3gd20hMinimal gyro(conn);

    while (1) {
        float x, y, z;
        gyro.gyro(x, y, z);  // Read angular rate, () -> (float, float, float) rad/s
        printf("x=%.3f y=%.3f z=%.3f rad/s\n", x, y, z);
        k_sleep(K_MSEC(100));
    }
    return 0;
}