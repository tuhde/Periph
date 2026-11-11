#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "ADS7828.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x48);
    ADS7828Minimal adc(connection);                         // Create ADS7828 driver, (connection, vref=0 → internal 2.5 V)

    stdio_init_all();
    while (true) {
        uint16_t raw = adc.readRaw(0);                              // Read CH0 raw code, (channel=0–7) → uint16_t
        float v0 = adc.readVoltage(0);                              // Read CH0 voltage, (channel=0–7) → float V
        float all[8];
        adc.readAll(all);                                           // Read all eight channels, (out[8]) → void
        printf("raw=%u v0=%.3f V ch7=%.3f V\n", raw, (double)v0, (double)all[7]);
        sleep_ms(1000);
    }
    return 0;
}
