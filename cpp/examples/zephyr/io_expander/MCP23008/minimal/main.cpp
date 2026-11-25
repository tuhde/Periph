#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "MCP23008.h"

#ifndef MCP23008_I2C_NODE
#define MCP23008_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MCP23008_ADDR
#define MCP23008_ADDR 0x20
#endif

static MCP23008Minimal* g_chip = nullptr;

int main(void) {
    const struct device *dev = DEVICE_DT_GET(MCP23008_I2C_NODE);
    I2CConnectionZephyr connection(dev, MCP23008_ADDR);

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

    printk("GPIO=0x%02X  GP0=%d\n", port_val, val);
    return 0;
}
