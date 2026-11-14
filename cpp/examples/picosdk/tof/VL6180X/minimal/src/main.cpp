#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "VL6180X.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, VL6180XMinimal::I2C_ADDRESS, nullptr, nullptr, 2);
    VL6180XMinimal sensor(connection);                      // Create VL6180X driver, (connection)
    sensor.init();                                          // Initialize, () → bool

    while (1) {
        uint8_t mm = 0;
        if (sensor.read_range(mm)) {                        // Single-shot range, (uint8_t& mm) → bool
            printf("%u mm\n", (unsigned)mm);
        } else {
            printf("range error %u\n", (unsigned)sensor.last_error()); // Last range error code, () → uint8_t
        }
        sleep_ms(200);
    }
    return 0;
}
