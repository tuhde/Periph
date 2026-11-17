#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "HT16K33.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x70);
    HT16K33Minimal matrix(connection);                      // Create HT16K33 driver, (connection)
    stdio_init_all();
    sleep_ms(2000);

    for (uint8_t row = 0; row < HT16K33Minimal::ROWS; row++) {
        matrix.set_pixel(0, row);                         // Set one LED in the shadow RAM, (com 0–7, row 0–15, on) → bool
    }
    matrix.show();                                        // Write the shadow RAM to the chip, () → void
    sleep_ms(1000);

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
