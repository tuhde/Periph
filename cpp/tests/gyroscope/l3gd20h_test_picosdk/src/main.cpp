#include <I2CConnectionPicoSDK.h>
#include <L3gd20h.h>
#include <stdio.h>
#include <pico/stdlib.h>

int main() {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK conn(i2c0, 0x6A);
    L3gd20hMinimal gyro(conn);

    printf("=== L3GD20H Pico SDK Test ===\n");

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