#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "PCA9536.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x41);
    stdio_init_all();

    PCA9536Minimal chip(connection);                        // Create PCA9536 driver, (connection, addr=0x41)
    stdio_init_all();
    auto p2 = chip.pin(2);                                      // Get P2 proxy, (n) → IOExpanderPin
    auto p0 = chip.pin(0);                                      // Get P0 proxy, (n) → IOExpanderPin

    p0.mode(OUTPUT);                                            // Set pin direction, (m INPUT/OUTPUT) → bool
    p0.high();                                                  // Drive P0 high, () → void
    p2.mode(INPUT);                                             // Set pin direction, (m INPUT/OUTPUT) → bool
    uint8_t val = p2.read();                                    // Read pin level, () → uint8_t 0|1

    chip.write_port(0, 0x09);                                   // Write the output latch, (port=0, mask) → void
    uint8_t port_val = chip.read_port();                        // Read all 4 pins, (port=0) → uint8_t bitmask

    printf("PORT=0x%X  P2=%d\n", port_val, val);
    while (true) sleep_ms(1000);
}
