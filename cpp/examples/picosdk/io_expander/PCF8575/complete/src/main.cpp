#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "InputPinPicoSDK.h"
#include "PCF8575.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    InputPinPicoSDK intPin(6);                                  // Create INT pin, (pin=6)
    I2CConnectionPicoSDK connection(i2c0, 0x20, &intPin);       // Create I2C connection, (i2c, addr=0x20, intPin)
    PCF8575Full chip(connection, /*addr=*/0x20);

    PCF8575Full::IOExpanderPin p0 = chip.pin(0);                   // Get pin proxy, (n=0) → IOExpanderPin
    PCF8575Full::IOExpanderPin p8 = chip.pin(8);                   // Get pin proxy, (n=8) → IOExpanderPin

    stdio_init_all();

    p0.mode(OUTPUT);                                            // Set direction, (mode=OUTPUT) → void
                                                               // drives P00 low (safe initial state for output)
    p0.high();                                                  // Set high, () → void
                                                               // releases to quasi-input; not strong drive
    p0.low();                                                   // Drive low, () → void
                                                               // strong pull-down, up to 25 mA
    p0.toggle();                                                // Invert shadow bit, () → void

    uint8_t v = p0.read();                                      // Read actual level, () → uint8_t

    chip.write_port(0, 0b00001111);                             // Write Port 0, (port=0, mask=uint8_t) → void
    chip.write_port(1, 0b00001111);                             // Write Port 1, (port=1, mask=uint8_t) → void

    p8.mode(INPUT);                                             // Set direction, (mode=INPUT) → void
    uint8_t state = p8.read();                                  // Read actual level, () → uint8_t

    chip.onInterrupt([](uint16_t changed) {
        printf("changed: %u\n", changed);
    });                                                          // Subscribe to INT line, (callback) → void

    uint16_t changed = chip.pollInterrupt();                     // Read port and return 16-bit changed bitmask, () → uint16_t
    return 0;
}
