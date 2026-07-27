#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "PCF8575.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x20);
    PCF8575Minimal chip(connection, /*addr=*/0x20);

    PCF8575Minimal::IOExpanderPin p0 = chip.pin(0);                // Get pin proxy, (n=0) → IOExpanderPin
    PCF8575Minimal::IOExpanderPin p8 = chip.pin(8);                // Get pin proxy, (n=8) → IOExpanderPin

    stdio_init_all();

    p0.mode(OUTPUT);                                            // Set direction, (mode=OUTPUT) → void
    p8.mode(INPUT);                                             // Set direction, (mode=INPUT) → void
    while (true) {

    uint8_t port0 = chip.read_port(0);                          // Read Port 0, (port=0) → uint8_t bitmask
    uint8_t port1 = chip.read_port(1);                           // Read Port 1, (port=1) → uint8_t bitmask
    if ((port1 >> 0) & 1) p0.high(); else p0.low();             // Set high, () → void / Set low, () → void
    sleep_ms(200);
        sleep_ms(10);
    }

    return 0;
}
