#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "TSL2561.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, TSL2561Minimal::I2C_ADDRESS);
    TSL2561Minimal sensor(connection);                  // Create TSL2561 driver, (connection)
    sensor.init();                                      // Power up, 1× gain, 402 ms, wait one integration, () → bool
    for (int i = 0; i < 10; i++) {
        float lx = sensor.readLux();                    // Read illuminance, () → float lux
        printf("lux %.1f lx\n", (double)lx);
        sleep_ms(1000);
    }
    return 0;
}
