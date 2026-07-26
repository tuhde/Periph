#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "AS5600.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x36);
    AS5600Minimal as5600(transport);

    stdio_init_all();
    while (true) {

    float a = as5600.angle();        // Read absolute angle, () → float degrees
    uint16_t r = as5600.angle_raw(); // Read scaled angle count, () → int 0-4095
    printf("angle="); printf("%.2f", a); printf("°  raw="); printf("%d\n", r);
    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}
