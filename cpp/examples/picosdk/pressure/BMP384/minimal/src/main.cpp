#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "BMP384.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x76);
    BMP384Minimal bmp(connection, /*spi=*/false);          // Create BMP384 driver, (connection, spi=false)

    stdio_init_all();
    sleep_ms(2000);

    for (int i = 0; i < 5; i++) {
        float t = bmp.temperature();                        // Read temperature, () → float °C
        float p = bmp.pressure();                          // Read pressure, () → float hPa
        printf("%.1f C, %.1f hPa\n", t, p);
        sleep_ms(1000);
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    while (true) sleep_ms(1000);
    return 0;
}
