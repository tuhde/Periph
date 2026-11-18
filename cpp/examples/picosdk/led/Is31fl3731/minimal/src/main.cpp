#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Is31fl3731.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x74);
    Is31fl3731Minimal matrix(connection);                 // Create IS31FL3731 driver, (connection)
    stdio_init_all();
    sleep_ms(2000);

    for (uint8_t col = 0; col < Is31fl3731Minimal::COLS; col++) {
        matrix.set_pixel(0, col, 128);                    // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → bool
    }
    sleep_ms(1000);
    matrix.fill(32);                                      // Set every LED's PWM brightness, (brightness 0–255) → void
    sleep_ms(1000);
    matrix.clear();                                       // Switch all LEDs off, () → void

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
