#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "BNO055.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x29);
    BNO055Minimal imu(connection);                                      // Create BNO055 driver, (connection) → void
    float h, r, p;

    stdio_init_all();
    while (true) {
        imu.euler(h, r, p);                                             // Read fused Euler angles, (heading, roll, pitch) → void rad
        printf("heading %.2f rad, roll %.2f, pitch %.2f\n", h, r, p);
        sleep_ms(100);
    }
    return 0;
}
