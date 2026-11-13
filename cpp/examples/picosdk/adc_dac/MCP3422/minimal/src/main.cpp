#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "MCP3422.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, MCP3422Minimal::I2C_ADDRESS);
    MCP3422Minimal adc(connection);                     // Create MCP3422 driver, (connection)
    for (int i = 0; i < 10; i++) {
        int32_t raw0 = adc.readRaw(0);                  // Read a signed code, (channel 0–1) → int32_t
        float v1 = adc.readVoltage(1);                  // Read the differential voltage, (channel 0–1) → float V
        printf("CH1 raw %d, CH2 %.6f V\n", (int)raw0, (double)v1);
        float both[2];
        bool ok = adc.readAll(both);                    // Read both channels, (float out[2]) → bool
        printf("all %d %.6f %.6f\n", (int)ok, (double)both[0], (double)both[1]);
        sleep_ms(1000);
    }
    return 0;
}
