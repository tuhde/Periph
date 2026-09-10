#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "ADXL345.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x53);
    ADXL345Minimal accel(connection);                       // Create ADXL345 driver, (connection, spi=false)

    stdio_init_all();
    while (true) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        printf("x=%.3f y=%.3f z=%.3f g\n", (double)x, (double)y, (double)z);
        sleep_ms(100);
    }

    return 0;
}