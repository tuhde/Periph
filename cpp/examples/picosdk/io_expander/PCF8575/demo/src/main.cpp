#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "PCF8575.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x20);
    PCF8575Full chip(transport, /*addr=*/0x20);

    stdio_init_all();

    // Configure output pins for LEDs (P00–P07 active-low)
    chip.write_port(0, 0xFF);                                   // Write Port 0, (port=0, mask=uint8_t) → void
    chip.write_port(1, 0xFF);                                   // Write Port 1, (port=1, mask=uint8_t) → void

    chip.configure_interrupt(5, [](uint8_t changed) {
        (void)changed;
    });                                                          // Attach interrupt, (int_gpio_pin, callback) → void
    while (true) {

    uint8_t port0 = chip.read_port(0);                          // Read Port 0, (port=0) → uint8_t bitmask
    uint8_t port1 = chip.read_port(1);                         // Read Port 1, (port=1) → uint8_t bitmask

    uint8_t buttons = port1;                                    // P10–P17 (pressed = 0)
    uint8_t led_bits = ~buttons;                                // invert: pressed → LED on (0)
    chip.write_port(0, led_bits);                               // Write Port 0, (port=0, mask=uint8_t) → void

    printf("0x%X", (unsigned)"P0=0x"); printf("%d", port0);
    printf("0x%X", (unsigned)"  P1=0x"); printf("%d", port1);
    printf("  buttons=");
    for (int i = 7; i >= 0; i--) printf("%d", (buttons >> i) & 1 ? '.' : 'X');
    printf("  LEDs=");
    for (int i = 7; i >= 0; i--) printf("%d", (led_bits >> i) & 1 ? ' ' : '*');
    printf("\n");
    sleep_ms(200);
        sleep_ms(10);
    }

    return 0;
}
