#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "APDS9960.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x39);
    APDS9960Full apds(connection);

    stdio_init_all();

    // --- Monitor ambient light with adaptive integration time ---
    // Start with the default 200 ms integration (ATIME=0xB6). When the clear
    // channel approaches saturation, halve the integration time to prevent overflow.
    apds.configure_als(0xB6, 1);                           // Configure ALS, (atime 0-255, again 0-3) → void
    while (true) {

    while (!apds.is_als_valid()) {                         // Check ALS data valid, () → bool
        sleep_ms(10);
    }

    uint16_t c, r, g, b;
    apds.color(c, r, g, b);                                // Read all RGBC channels, (clear, red, green, blue) → void
    float lux = -0.32466f * r + 1.57837f * g + -0.73191f * b;
    printf("C="); printf("%d", c);
    printf(" R="); printf("%d", r);
    printf(" G="); printf("%d", g);
    printf(" B="); printf("%d", b);
    printf("  lux~"); printf("%.0f\n", lux);

    // --- Adaptive integration: reduce time when saturated ---
    // At saturation the sensor clips; shortening integration recovers
    // headroom at the cost of reduced sensitivity in low light.
    if (apds.is_als_saturated()) {                         // Check ALS saturated, () → bool
        printf("[SATURATED — reducing integration time]\n");
        apds.configure_als(0xFE, 1);                       // Configure ALS, (atime 0-255, again 0-3) → void
        sleep_ms(250);
    }

    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}
