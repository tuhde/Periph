#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "MCP4728.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x60);
    MCP4728Minimal dac(transport);

    stdio_init_all();
    while (true) {

    dac.set_voltage(0, 0.5f);
    dac.set_raw(1, 2048);
    float fractions[4] = {0.0f, 0.25f, 0.5f, 1.0f};
    dac.set_all(fractions);
    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}
