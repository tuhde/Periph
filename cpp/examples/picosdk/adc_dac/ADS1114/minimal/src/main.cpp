#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "ADS1114.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, ADS1114Minimal::I2C_ADDRESS);
    ADS1114Minimal adc(connection);                         // Create ADS1114 driver, (connection)

    while (1) {
        float v = adc.readVoltage();                        // Read voltage, () → float V
        printf("%.5f V\n", (double)v);
        sleep_ms(1000);
    }
    return 0;
}
