#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LIS3DH.h"

static void lis3dh_sleep_ms(uint32_t ms) { sleep_ms(ms); }

int main(void) {
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    stdio_init_all();

    I2CConnectionPicoSDK connection(i2c0, 0x18);
    LIS3DHMinimal accel(connection);                            // Create LIS3DH driver, (connection)
    for (int i = 0; i < 10; i++) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        printf("x=%.3f y=%.3f z=%.3f g\n", x, y, z);
        lis3dh_sleep_ms(100);
    }
    return 0;
}
