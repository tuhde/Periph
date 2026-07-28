#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "BMP180.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x77);
    BMP180Full bmp(connection, /*oss=*/3);

    static int passed = 0, failed = 0;

    stdio_init_all();
    sleep_ms(2000);

    float t0 = bmp.temperature();                     // Read temperature, () → float C
    float p0 = bmp.pressure();                       // Read pressure, () → float hPa
    float alt_ref = bmp.altitude();                  // Compute altitude, (sea_level_hpa=1013.25) → float m
    printf("Reference: ");
    printf("%.1f", t0);
    printf(" C, ");
    printf("%.1f", p0);
    printf(" hPa, alt=");
    printf("%.1f", alt_ref);
    printf(" m\n");

    float prev_alt = 0.0f;
    for (int n = 0; n < 60; n++) {
        float t = bmp.temperature();                 // Read temperature, () → float C
        float p = bmp.pressure();                  // Read pressure, () → float hPa
        float a = bmp.altitude();                 // Compute altitude, (sea_level_hpa=1013.25) → float m
        float da = (a - prev_alt) * 100.0f;

        if (n > 0) {
            printf("%d", n);
            printf("s: ");
            printf("%.1f", t);
            printf(" C, ");
            printf("%.1f", p);
            printf(" hPa, alt=");
            printf("%.1f", a);
            printf(" m (delta=");
            printf("%.0f", da);
            printf(" cm)\n");
        } else {
            printf("%d", n);
            printf("s: ");
            printf("%.1f", t);
            printf(" C, ");
            printf("%.1f", p);
            printf(" hPa, alt=");
            printf("%.1f", a);
            printf(" m\n");
        }
        prev_alt = a;
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
