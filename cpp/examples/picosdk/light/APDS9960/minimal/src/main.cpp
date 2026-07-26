#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "APDS9960.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x39);
    APDS9960Minimal apds(transport);

    stdio_init_all();
    while (true) {

    uint16_t c, r, g, b;
    apds.color(c, r, g, b);                                // Read all RGBC channels, (clear, red, green, blue) → void
    printf("C="); printf("%d", c);
    printf(" R="); printf("%d", r);
    printf(" G="); printf("%d", g);
    printf(" B="); printf("%d\n", b);
    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}
