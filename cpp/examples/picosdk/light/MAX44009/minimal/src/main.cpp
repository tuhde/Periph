#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "MAX44009.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, MAX44009Minimal::I2C_ADDRESS);
    MAX44009Minimal sensor(connection);                 // Create MAX44009 driver, (connection)
    for (int i = 0; i < 10; i++) {
        float lx = sensor.lux();                        // Read illuminance, () → float lux
        printf("lux %.2f lx\n", (double)lx);
        sleep_ms(1000);
    }
    return 0;
}
