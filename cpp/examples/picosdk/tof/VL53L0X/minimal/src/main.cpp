#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "VL53L0X.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, VL53L0XMinimal::I2C_ADDRESS);
    VL53L0XMinimal sensor(connection);                      // Create VL53L0X driver, (connection)

    while (1) {
        uint16_t d = sensor.distance();                     // Measure distance, () → uint16_t mm
        if (sensor.rangeValid()) {                          // Check last measurement, () → bool
            printf("%u mm\n", (unsigned)d);
        } else {
            printf("out of range\n");
        }
        sleep_ms(100);
    }
    return 0;
}
