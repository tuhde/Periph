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
    BMP280Full bmp(transport, /*spi=*/false);

    static int passed = 0, failed = 0;

    stdio_init_all();
    sleep_ms(2000);

    // --- Weather monitoring preset: lowest power, forced mode ---
    // BMP280 datasheet Table 7: ×1/×1, filter off, forced mode.
    // One sample per second for 30 seconds.
    bmp.configure(BMP280Full::OSRS_X1, BMP280Full::OSRS_X1, BMP280Full::MODE_FORCED, BMP280Full::FILTER_OFF, BMP280Full::T_SB_0_5_MS);  // Configure chip, (osrs_t=×1, osrs_p=×1, mode=forced, filter=off, t_sb=0) → None

    for (int n = 0; n < 30; n++) {
        float t = bmp.temperature();                     // Read temperature, () → float °C
        float p = bmp.pressure();                       // Read pressure, () → float hPa
        float a = bmp.altitude();                      // Compute altitude, (sea_level_hpa=1013.25) → float m
        printf("%d", n);
        printf("s: ");
        printf("%.1f", t);
        printf(" C, ");
        printf("%.1f", p);
        printf(" hPa, alt=");
        printf("%.1f", a);
        printf(" m\n");
        sleep_ms(1000);
    }

    // --- Indoor navigation preset: high resolution with IIR filter ---
    // ×16/×2, filter coefficient 16, normal mode at ~26 Hz.
    bmp.configure(BMP280Full::OSRS_X2, BMP280Full::OSRS_X16, BMP280Full::MODE_NORMAL, BMP280Full::FILTER_16, BMP280Full::T_SB_0_5_MS);  // Configure chip, (osrs_t=×2, osrs_p=×16, mode=normal, filter=16, t_sb=0.5ms) → None

    for (int n = 0; n < 30; n++) {
        float t = bmp.temperature();                     // Read temperature, () → float °C
        float p = bmp.pressure();                       // Read pressure, () → float hPa
        float a = bmp.altitude();                      // Compute altitude, (sea_level_hpa=1013.25) → float m
        printf("%d", n);
        printf("s: alt=");
        printf("%.4f", a);
        printf(" m\n");
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
