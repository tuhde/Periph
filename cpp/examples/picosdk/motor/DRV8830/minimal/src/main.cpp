#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "DRV8830.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, DRV8830Minimal::I2C_ADDRESS);
    DRV8830Minimal motor(connection);                       // Create DRV8830 driver, (connection)

    while (1) {
        motor.drive(3.0f);                                  // Drive at regulated voltage, (voltage V, + = forward) → void
        sleep_ms(2000);
        motor.drive(-3.0f);                                 // Drive at regulated voltage, (voltage V, - = reverse) → void
        sleep_ms(2000);
    }
    return 0;
}
