#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "MCP23008.h"

static MCP23008Minimal* g_chip = nullptr;

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x20);
    stdio_init_all();

    MCP23008Minimal chip(connection);                         // Create MCP23008 driver, (connection, addr=0x20)
    g_chip = &chip;
    auto p0 = chip.pin(0);                                    // Get GP0 proxy, (n) → IOExpanderPin
    auto p7 = chip.pin(7);                                    // Get GP7 proxy, (n) → IOExpanderPin

    p7.mode(OUTPUT);                                          // Set pin direction, (m INPUT/OUTPUT) → void
    p7.high();                                                // Drive GP7 high, () → void
    p0.mode(INPUT);                                           // Set pin direction, (m INPUT/OUTPUT) → void
    uint8_t val = p0.read();                                  // Read pin level, () → uint8_t 0|1

    chip.write_port(0, 0x81);                                 // Write the output latch, (port=0, mask) → void
    uint8_t port_val = chip.read_port();                      // Read all 8 pins, (port=0) → uint8_t bitmask

    printf("GPIO=0x%02X  GP0=%d\n", port_val, val);
    while (true) sleep_ms(1000);
}
