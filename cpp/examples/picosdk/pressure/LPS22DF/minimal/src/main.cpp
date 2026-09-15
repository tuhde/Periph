#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LPS22DF.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x5C);
    LPS22DFMinimal lps(connection, /*spi=*/false);        // Create LPS22DF driver, (connection, spi=false)

    stdio_init_all();
    sleep_ms(2000);

    for (int i = 0; i < 5; i++) {
        float p = lps.pressure();                         // Read pressure, () → float Pa
        float t = lps.temperature();                      // Read temperature, () → float °C
        printf("%.1f", t);
        printf(" C, ");
        printf("%.0f", p);
        printf(" Pa\n");
        sleep_ms(1000);
    }
    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) sleep_ms(1000);
    return 0;
}