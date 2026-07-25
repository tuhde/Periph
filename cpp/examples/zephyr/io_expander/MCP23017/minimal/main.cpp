#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "MCP23017.h"

#ifndef MCP23017_I2C_NODE
#define MCP23017_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MCP23017_ADDR
#define MCP23017_ADDR 0x20
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(MCP23017_I2C_NODE);
    I2CTransportZephyr transport(dev, MCP23017_ADDR);
    MCP23017Minimal mcp(transport);                        // Create MCP23017 driver, (transport, addr=0x20)

    auto p7 = mcp.pin(7);                                   // Get pin 7 (GPA7 output-only), () → IOExpanderPin
    p7.mode(OUTPUT);                                      // Set pin direction, (mode=OUTPUT) → None
    p7.low();                                             // Drive pin 7 low, () → None

    auto p0 = mcp.pin(0);                                  // Get pin 0 as input, () → IOExpanderPin
    p0.mode(INPUT);                                        // Set pin direction, (mode=INPUT) → None
    uint8_t val = p0.read();                               // Read pin level, () → uint8_t 0|1

    mcp.write_port(0, 0x01);                              // Write PORTA mask, (port=0, mask) → None
    uint8_t port_val = mcp.read_port(0);                  // Read PORTA, (port=0) → uint8_t 0–255

    printk("PORTA=%02X  pin0=%d\n", port_val, val);
    return 0;
}