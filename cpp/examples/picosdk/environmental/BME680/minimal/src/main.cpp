#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "BME680.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x77);
    BME680Minimal bme(transport);

    stdio_init_all();
    sleep_ms(2000);

    for (int i = 0; i < 5; i++) {
        float t = bme.temperature();                     // Read temperature, () → float °C
        float p = bme.pressure();                       // Read pressure, () → float hPa
        float h = bme.humidity();                       // Read humidity, () → float %RH
        float g = bme.gas_resistance();                 // Read gas resistance, () → float Ω
        printf("%.1f", t);
        printf(" C, ");
        printf("%.1f", p);
        printf(" hPa, ");
        printf("%.1f", h);
        printf(" %RH, ");
        printf("%.0f", g);
        printf(" Ohm\n");
        sleep_ms(5000);
    }

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
