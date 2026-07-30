#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "RDA5807M.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x10);
    RDA5807MMinimal rda5807m(connection, /*frequency_mhz=*/100.0f, /*volume=*/5);

    stdio_init_all();
    while (true) {

    float freq;
    if (fm.seek(true, freq)) {                            // Seek to next station, (up=true, frequency_mhz) → bool
        printf("%f\n", freq);
    }
    sleep_ms(3000);
        sleep_ms(10);
    }

    return 0;
}
