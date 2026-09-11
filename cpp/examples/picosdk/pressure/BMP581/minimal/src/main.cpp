#include <stdio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "BMP581.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x46);
    BMP581Minimal bmp(connection, /*spi=*/false);

    stdio_init_all();
    sleep_ms(2000);

    for (int i = 0; i < 5; i++) {
        float p = bmp.pressure();                         // Read pressure, () → float Pa
        float t = bmp.temperature();                      // Read temperature, () → float °C
        printf("%.1f C, %.1f Pa\n", t, p);
        sleep_ms(1000);
    }

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) sleep_ms(1000);

    return 0;
}