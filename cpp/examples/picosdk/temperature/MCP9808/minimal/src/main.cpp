#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "MCP9808.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, MCP9808Minimal::I2C_ADDRESS);
    MCP9808Minimal sensor(connection);                      // Create MCP9808 driver, (connection)

    while (1) {
        float t = sensor.readTemperature();                     // Read ambient temperature, () → °C
        printf("%.4f C\n", (double)t);
        sleep_ms(1000);
    }
    return 0;
}
