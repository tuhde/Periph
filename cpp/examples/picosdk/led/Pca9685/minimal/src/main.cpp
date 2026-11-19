#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Pca9685.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x40);

    Pca9685Minimal pwm(connection);                   // Create PCA9685 driver, (connection)
    stdio_init_all();
    sleep_ms(2000);

    for (uint8_t step = 0; step <= 10; step++) {
        pwm.set_duty(0, step / 10.0f);                    // Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → bool
        sleep_ms(200);
    }
    pwm.set_duty(0, 0.0f);

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
