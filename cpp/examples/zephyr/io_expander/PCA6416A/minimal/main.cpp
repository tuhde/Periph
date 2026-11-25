#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "PCA6416A.h"

#ifndef PCA6416A_I2C_NODE
#define PCA6416A_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef PCA6416A_ADDR
#define PCA6416A_ADDR 0x20
#endif

static PCA6416AMinimal* g_chip = nullptr;

int main(void) {
    const struct device *dev = DEVICE_DT_GET(PCA6416A_I2C_NODE);
    I2CConnectionZephyr connection(dev, PCA6416A_ADDR);

    PCA6416AMinimal chip(connection);                         // Create PCA6416A driver, (connection, addr=0x20)
    g_chip = &chip;

    auto p0 = chip.pin(0);                                    // Get P0_0 proxy, (n) → IOExpanderPin
    auto p7 = chip.pin(15);                                    // Get P1_7 proxy, (n) → IOExpanderPin

    p7.mode(OUTPUT);                                          // Set pin direction, (m INPUT/OUTPUT) → bool
    p7.high();                                                // Drive P1_7 high, () → void
    p0.mode(INPUT);                                           // Set pin direction, (m INPUT/OUTPUT) → bool
    uint8_t val = p0.read();                                  // Read pin level, () → uint8_t 0|1

    chip.write_port(0, 0x81);                                 // Write port 0 output latch, (port, mask) → void
    uint8_t port_val = chip.read_port(1);                      // Read the 8 pins of port 1, (port) → uint8_t bitmask

    printk("PORT1=0x%02X  P0=%d\n", port_val, val);
    return 0;
}
