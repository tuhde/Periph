#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "DS3231.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, DS3231Minimal::I2C_ADDRESS);
    DS3231Minimal rtc(connection);                          // Create DS3231 driver, (connection)

    stdio_init_all();
    while (true) {
        DS3231Minimal::DateTime dt;
        rtc.getDatetime(dt);                                // Read calendar clock, () → DateTime
        float tempC = rtc.readTemperature();                // Read on-chip temperature, () → float C
        printf("%04u-%02u-%02u %02u:%02u:%02u  %.2f C\n",
               dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second, (double)tempC);
        sleep_ms(1000);
    }

    return 0;
}
