#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "PCF8574.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x20);
    PCF8574Minimal chip(transport, /*addr=*/0x20);

    PCF8574Minimal::IOExpanderPin p0 = chip.pin(0);               // Get pin proxy, (n) → IOExpanderPin
    PCF8574Minimal::IOExpanderPin p4 = chip.pin(4);               // Get pin proxy, (n) → IOExpanderPin

    stdio_init_all();

    p0.mode(OUTPUT);                                           // Set direction, (mode=OUTPUT) → void
    p4.mode(INPUT);                                            // Set direction, (mode=INPUT) → void
    while (true) {

    uint8_t port = chip.read_port();                           // Read all 8 pins, () → uint8_t bitmask
    if ((port >> 4) & 1) p0.high(); else p0.low();            // Set high, () → void / Set low, () → void
    sleep_ms(200);
        sleep_ms(10);
    }

    return 0;
}
