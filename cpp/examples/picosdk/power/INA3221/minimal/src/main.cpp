#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "INA3221.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x40);
    INA3221Minimal ina(connection, /*r_shunt=*/0.1f);

    stdio_init_all();
    while (true) {

    for (uint8_t ch = 1; ch <= 3; ch++) {
        printf("%d", ina.voltage(ch));   printf("V  ");  // Read bus voltage, (channel) → V
        printf("%d", ina.current(ch));   printf("A  ");  // Read load current, (channel) → A
        printf("%d", ina.power(ch));     printf("W  ");  // Read power, (channel) → W
    }
    printf("\n");
    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}
