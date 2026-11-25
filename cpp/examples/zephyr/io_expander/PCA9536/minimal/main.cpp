#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "PCA9536.h"

#ifndef PCA9536_I2C_NODE
#define PCA9536_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef PCA9536_ADDR
#define PCA9536_ADDR 0x41
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(PCA9536_I2C_NODE);
    I2CConnectionZephyr connection(dev, PCA9536_ADDR);

    PCA9536Minimal chip(connection);                        // Create PCA9536 driver, (connection, addr=0x41)
    auto p2 = chip.pin(2);                                      // Get P2 proxy, (n) → IOExpanderPin
    auto p0 = chip.pin(0);                                      // Get P0 proxy, (n) → IOExpanderPin

    p0.mode(OUTPUT);                                            // Set pin direction, (m INPUT/OUTPUT) → bool
    p0.high();                                                  // Drive P0 high, () → void
    p2.mode(INPUT);                                             // Set pin direction, (m INPUT/OUTPUT) → bool
    uint8_t val = p2.read();                                    // Read pin level, () → uint8_t 0|1

    chip.write_port(0, 0x09);                                   // Write the output latch, (port=0, mask) → void
    uint8_t port_val = chip.read_port();                        // Read all 4 pins, (port=0) → uint8_t bitmask

    printk("PORT=0x%X  P2=%d\n", port_val, val);
    while (1) { k_sleep(K_SECONDS(1)); }
    return 0;
}
