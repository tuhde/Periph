#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "InputPinPicoSDK.h"
#include "MCP23017.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    InputPinPicoSDK intPin(6);                                  // Create INT pin, (pin=6)
    I2CConnectionPicoSDK connection(i2c0, 0x20, &intPin);       // Create I2C connection, (i2c, addr=0x20, intPin)
    MCP23017Full mcp(connection, /*addr=*/0x20);

    stdio_init_all();

    mcp.configure_pullup(0, 0x3F);                          // Enable pull-ups on GPA0–GPA5, (port=0, mask=0x3F) → None
    mcp.configure_polarity(0, 0x00);                        // Set normal polarity PORTA, (port=0, mask=0x00) → None

    auto p7 = mcp.pin(7);                                    // GPA7 is output-only; get as output
    p7.mode(OUTPUT);                                        // Set pin direction, (mode=OUTPUT) → None
    p7.low();                                                // Drive GPA7 low, () → None

    for (uint8_t n = 0; n < 8; n++) {
        auto p = mcp.pin(n);                                 // Get pin n as input, (n) → IOExpanderPin
        p.mode(INPUT);                                       // Set pin direction, (mode=INPUT) → None
        uint8_t val = p.read();                              // Read pin level, () → uint8_t 0|1
        printf("GPA");
        printf("%d", n);
        printf("=");
        printf("%d", val);
        printf("  ");
    }
    printf("\n");

    mcp.write_port(0, 0x80);                               // Write GPA7 high, (port=0, mask=0x80) → None

    mcp.set_default_value(0, 0x00);                        // Set DEFVAL for PORTA, (port=0, mask=0x00) → None

    mcp.onInterrupt([](uint8_t port, uint8_t status) {      // Subscribe to INT on both ports, (callback, intPin, mirror) → None
        printf("port %d changed: 0x%X\n", port, (unsigned)status);
    }, &intPin, /*mirror=*/true);

    mcp.offInterrupt(0);                                    // Unsubscribe PORTA, (port=0) → None

    uint8_t porta = mcp.read_port(0);                       // Read PORTA, (port=0) → uint8_t
    uint8_t portb = mcp.read_port(1);                      // Read PORTB, (port=1) → uint8_t
    printf("PORTA=");
    printf("0x%X", (unsigned)porta);
    printf("  PORTB=");
    printf("0x%X\n", (unsigned)portb);
    return 0;
}
