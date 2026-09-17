#include <stdio.h>
#include "pico/stdlib.h"
#include "hardware/gpio.h"
#include "I2CConnectionPicoSDK.h"
#include "L3G4200D.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x68);
    L3G4200DMinimal chip(connection, /*spi=*/false);      // Create L3G4200D driver, (connection, spi=false)

    stdio_init_all();
    sleep_ms(2000);

    for (int i = 0; i < 10; i++) {
        float x, y, z;
        chip.angular_rate(x, y, z);                        // Read X/Y/Z angular rate, () → (float, float, float) rad/s
        printf("X=%.2f Y=%.2f Z=%.2f rad/s\n", x, y, z);
        sleep_ms(100);
    }
    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) sleep_ms(1000);
    return 0;
}
