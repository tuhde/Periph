#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Isl29125.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, Isl29125Minimal::I2C_ADDRESS);
    Isl29125Minimal sensor(connection);                             // Create ISL29125 driver, (connection)
    for (int i = 0; i < 10; i++) {
        Isl29125Counts c = {0, 0, 0};
        sensor.read(c);                                             // Read the three channels, (counts&) → bool
        printf("R %lu G %lu B %lu\n", (unsigned long)(c.red), (unsigned long)(c.green), (unsigned long)(c.blue));
        sleep_ms(1000);
    }
    return 0;
}
