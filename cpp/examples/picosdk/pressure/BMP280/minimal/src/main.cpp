#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "BMP280.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x76);
    BMP280Minimal bmp(transport, /*spi=*/false);

    static int passed = 0, failed = 0;

    stdio_init_all();
    sleep_ms(2000);

    for (int i = 0; i < 5; i++) {
        float t = bmp.temperature();                     // Read temperature, () → float °C
        float p = bmp.pressure();                       // Read pressure, () → float hPa
        printf("%.1f", t);
        printf(" C, ");
        printf("%.1f", p);
        printf(" hPa\n");
        sleep_ms(1000);
    }

    printf("===DONE: ");
    printf("%d", passed);
    printf(" passed, ");
    printf("%d", failed);
    printf(" failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
