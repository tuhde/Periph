#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "PCA9539.h"

static PCA9539Minimal* g_chip = nullptr;

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x74);
    stdio_init_all();

    PCA9539Minimal chip(connection);                         // Create PCA9539 driver, (connection, addr=0x74)
    g_chip = &chip;

    auto p0 = chip.pin(0);                                    // Get P00 proxy, (n) → IOExpanderPin
    auto p7 = chip.pin(15);                                    // Get P17 proxy, (n) → IOExpanderPin

    p7.mode(OUTPUT);                                          // Set pin direction, (m INPUT/OUTPUT) → bool
    p7.high();                                                // Drive P17 high, () → void
    p0.mode(INPUT);                                           // Set pin direction, (m INPUT/OUTPUT) → bool
    uint8_t val = p0.read();                                  // Read pin level, () → uint8_t 0|1

    chip.write_port(0, 0x81);                                 // Write port 0 output latch, (port, mask) → void
    uint8_t port_val = chip.read_port(1);                      // Read the 8 pins of port 1, (port) → uint8_t bitmask

    printf("PORT1=0x%02X  P0=%d\n", port_val, val);
    while (true) sleep_ms(1000);
}
