#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "PCF8523.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, PCF8523Minimal::I2C_ADDRESS);

    PCF8523Minimal rtc(connection);                          // Create PCF8523 driver, (connection)

    for (int i = 0; i < 10; ++i) {
        PCF8523Minimal::DateTime dt;
        rtc.getDatetime(dt);                                 // Read calendar clock, () → DateTime
        printf("%04u-%02u-%02u %02u:%02u:%02u\n",
            dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
        sleep_ms(1000);
    }
    return 0;
}
