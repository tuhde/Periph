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

    while (true) {
        float x, y, z;
        gyro.gyro(x, y, z);  // Read angular rate, () -> (float, float, float) rad/s
        printf("x=%.3f y=%.3f z=%.3f rad/s\n", x, y, z);
        sleep_ms(100);
    }
    return 0;
}