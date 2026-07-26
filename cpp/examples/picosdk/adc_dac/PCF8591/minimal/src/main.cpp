#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "PCF8591.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x48);
    PCF8591Minimal adc(transport);

    stdio_init_all();
    while (true) {

    uint8_t ch0 = adc.read_channel(0);                  // Read single channel, (channel=0–3) → uint8_t
    uint8_t raw[PCF8591Minimal::NUM_CHANNELS];
    adc.read_all(raw);                                  // Read all four channels, (out[4]) → None
    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}
