#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "TCA9554.h"

#ifndef TCA9554_I2C_NODE
#define TCA9554_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef TCA9554_ADDR
#define TCA9554_ADDR 0x20
#endif

static TCA9554Minimal* g_chip = nullptr;

int main(void) {
    const struct device *dev = DEVICE_DT_GET(TCA9554_I2C_NODE);
    I2CConnectionZephyr connection(dev, TCA9554_ADDR);

    TCA9554Minimal chip(connection);                         // Create TCA9554 driver, (connection, addr=0x20)
    g_chip = &chip;

    auto p0 = chip.pin(0);                                    // Get P0 proxy, (n) → IOExpanderPin
    auto p7 = chip.pin(7);                                    // Get P7 proxy, (n) → IOExpanderPin

    p7.mode(OUTPUT);                                          // Set pin direction, (m INPUT/OUTPUT) → bool
    p7.high();                                                // Drive P7 high, () → void
    p0.mode(INPUT);                                           // Set pin direction, (m INPUT/OUTPUT) → bool
    uint8_t val = p0.read();                                  // Read pin level, () → uint8_t 0|1

    chip.write_port(0, 0x81);                                 // Write the output latch, (port=0, mask) → void
    uint8_t port_val = chip.read_port();                      // Read all 8 pins, (port=0) → uint8_t bitmask

    printk("PORT=0x%02X  P0=%d\n", port_val, val);
    return 0;
}
