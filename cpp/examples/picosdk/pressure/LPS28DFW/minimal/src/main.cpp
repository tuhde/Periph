#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LPS28DFW.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x5C);
    LPS28DFWMinimal lps(connection);                       // Create LPS28DFW driver, (connection)

    stdio_init_all();
    sleep_ms(2000);

    for (int i = 0; i < 5; i++) {
        float t = lps.read_temperature();                  // Read temperature, () → float °C
        float p = lps.read_pressure();                     // Read pressure, () → float hPa
        printf("%.1f C, %.1f hPa\n", (double)t, (double)p);
        sleep_ms(1000);
    }

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) { sleep_ms(1000); }
    return 0;
}