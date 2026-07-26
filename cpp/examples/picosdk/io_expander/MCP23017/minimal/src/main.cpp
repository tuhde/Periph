#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "MCP23017.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x20);
    MCP23017Minimal mcp(transport, /*addr=*/0x20);

    stdio_init_all();

    auto p0 = mcp.pin(0);                                     // Get pin 0, () → IOExpanderPin
    auto p7 = mcp.pin(7);                                     // Get pin 7 (GPA7 output-only), () → IOExpanderPin

    p7.mode(OUTPUT);                                         // Set pin direction, (mode=OUTPUT) → None
    p7.low();                                                // Drive pin 7 low, () → None

    p0.mode(INPUT);                                          // Set pin direction, (mode=INPUT) → None
    uint8_t val = p0.read();                                 // Read pin level, () → uint8_t 0|1

    mcp.write_port(0, 0x01);                                 // Write PORTA mask, (port=0, mask) → None
    uint8_t port_val = mcp.read_port(0);                   // Read PORTA, (port=0) → uint8_t 0–255

    printf("PORTA=");
    printf("0x%X", (unsigned)port_val);
    printf("  pin0=");
    printf("%d\n", val);
    return 0;
}
