#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "PCF8576.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x70);
    PCF8576Minimal lcd(connection);

    stdio_init_all();
    sleep_ms(2000);

    static const uint8_t digits[] = {1, 2, 3, 4};
    for (uint8_t i = 0; i < 4; i++) {
        uint8_t seg = PCF8576Minimal::SEVEN_SEG[digits[i]];  // Encode 7-segment digit, (digit 0–9) → uint8_t
        lcd.set_digit_7seg(i, seg);                      // Write one digit, (position 0–19, segments 0–255) → void
    }

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
