#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "InputPinPicoSDK.h"
#include "PCF8574.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    InputPinPicoSDK intPin(6);                                  // Create INT pin, (pin=6)
    I2CConnectionPicoSDK connection(i2c0, 0x20, &intPin);       // Create I2C connection, (i2c, addr=0x20, intPin)
    PCF8574Full chip(connection, /*addr=*/0x20);

    stdio_init_all();

    PCF8574Full::IOExpanderPin p0 = chip.pin(0);               // Get full pin proxy, (n) → IOExpanderPin
                                                               // returned by value; holds reference to chip
    p0.mode(OUTPUT);                                           // Set direction output, (mode=OUTPUT) → void
                                                               // drives P0 low (initial state for output)
    p0.high();                                                 // Set high (release to quasi-input), () → void
                                                               // shadow |= (1 << 0); writes full shadow byte
    p0.low();                                                  // Drive low, () → void
                                                               // shadow &= ~(1 << 0); writes full shadow byte
    p0.toggle();                                               // Invert shadow bit, () → void
                                                               // reads actual pin then flips shadow
    p0.write(HIGH);                                            // Write pin, (v=HIGH|LOW) → void
                                                               // equivalent to high()
    uint8_t v = p0.read();                                     // Read actual level, () → uint8_t
                                                               // reads full port byte, returns bit n
    printf("%d\n", v);

    uint8_t mask = chip.read_port();                           // Read all 8 pins, (port=0) → uint8_t bitmask
                                                               // P0 in bit 0, P7 in bit 7
    chip.write_port(0, 0b00001111);                            // Write all 8 pins, (port, mask) → void
                                                               // P0–P3 low (outputs), P4–P7 high (inputs)

    PCF8574Full::IOExpanderPin p4 = chip.pin(4);               // Get full pin proxy, (n) → IOExpanderPin
    p4.mode(INPUT);                                            // Set direction input, (mode=INPUT) → void
                                                               // releases pin to quasi-input (shadow bit = 1)
    uint8_t state = p4.read();                                 // Read actual level, () → uint8_t
                                                               // 0 if button pulls P4 low, 1 if floating
    printf("%d\n", state);

    chip.onInterrupt([](uint8_t changed) {                     // Subscribe to INT line, (callback) → void
        printf("INT changed=0x%X\n", (unsigned)changed);       // callback fires on any input change
    });

    uint8_t changed = chip.pollInterrupt();                    // Read and return changed bitmask, () → uint8_t
                                                               // compares current byte to previous; clears INT
    printf("0x%X\n", (unsigned)changed);

    PCF8574Full::IOExpanderPin p5 = chip.pin(5);              // Get full pin proxy, (n) → IOExpanderPin
    p5.mode(INPUT);
    p5.watch([](PCF8574Full::IOExpanderPin* p) {              // Subscribe to pin edges, (handler, trigger) → void
        printf("P5 fell\n");                                   // fires when P5 transitions to match trigger
    }, InputPin::kFalling);
    p5.unwatch();                                               // Unsubscribe pin handler, () → void
    while (true) {

    sleep_ms(200);
        sleep_ms(10);
    }

    return 0;
}
