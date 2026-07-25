#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "BME280.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x76);
BME280Minimal bme(transport, /*spi=*/false);

int main(void) {

    stdio_init_all();
    sleep_ms(2000);


    for (int i = 0; i < 5; i++) {
        float t = bme.temperature();                    // Read temperature, () → float °C
        float p = bme.pressure();                      // Read pressure, () → float hPa
        float h = bme.humidity();                      // Read humidity, () → float %RH
        printf("%.1f", t);
        printf(" C, ");
        printf("%.1f", p);
        printf(" hPa, ");
        printf("%.1f", h);
        printf(" %RH\n");
        sleep_ms(1000);
    }

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
