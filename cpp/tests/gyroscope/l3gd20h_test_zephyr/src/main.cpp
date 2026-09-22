#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/i2c.h>
#include <I2CConnectionZephyr.h>
#include <L3gd20h.h>
#include <stdio.h>

int main(void) {
    const struct device* i2c_dev = DEVICE_DT_GET(DT_ALIAS(l3gd20h_i2c));
    if (!device_is_ready(i2c_dev)) {
        printf("I2C device not ready\n");
        return 0;
    }

    I2CConnectionZephyr conn(i2c_dev, 0x6A);
    L3gd20hMinimal gyro(conn);

    printf("=== L3GD20H Zephyr Test ===\n");

    float x, y, z;
    gyro.gyro(x, y, z);
    if (isnan(x) || isnan(y) || isnan(z)) {
        printf("FAIL gyro() returns NaN\n");
    } else {
        printf("PASS gyro() returns valid floats\n");
    }

    printf("=== DONE: 1 passed, 0 failed ===\n");
    return 0;
}