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

// Callbacks are plain function pointers (no std::function, no heap).
static void onChange(uint8_t changed) {
    printk("changed=0x%X\n", changed);
}
static void onPin(PCA9536Full::IOExpanderPin*) {
    printk("P2 falling\n");
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(PCA9536_I2C_NODE);
    I2CConnectionZephyr connection(dev, PCA9536_ADDR);

    PCA9536Full chip(connection);                           // Create PCA9536 driver, (connection, addr=0x41)
                                                            // writes the reset values; all pins start as inputs
    chip.configure_polarity(0x00);                              // Normal input polarity, (mask=0x00) → void
                                                            // writes POLARITY; 1 bits invert the INPUT read
    chip.set_direction(0x0C);                                   // P0–P1 outputs, P2–P3 inputs, (mask=0x0C) → void
                                                            // OUTPUT is written before CONFIG (no glitch)
    uint8_t dir = chip.get_direction();                         // Read the direction shadow, () → uint8_t
                                                            // 1 = input, 0 = output
    printk("CONFIG=0x%X\n", dir);

    for (uint8_t n = 0; n < 2; n++) {
        auto p = chip.pin(n);                                   // Get LED pin proxy, (n) → IOExpanderPin
                                                            // Full proxy with watch support
        p.mode(OUTPUT);                                         // Set pin direction, (m INPUT/OUTPUT) → bool
                                                            // latches OUTPUT, then clears the CONFIG bit
        p.low();                                                // Drive pin low, () → void
                                                            // clears the OUTPUT bit via the shadow
        p.toggle();                                             // Invert the output latch, () → void
                                                            // one OUTPUT write, no read-modify-write
    }

    chip.write_port(0, 0x01);                                   // Write the output latch, (port=0, mask) → void
                                                            // whole-port OUTPUT write, updates the shadow
    uint8_t port_val = chip.read_port();                        // Read all 4 pins, (port=0) → uint8_t bitmask
                                                            // reads INPUT (actual pin levels)
    printk("PORT=0x%X\n", port_val);
    uint8_t config = chip.read_register(0x03);                  // Read raw register, (reg) → uint8_t
                                                            // escape hatch for debugging
    printk("CONFIG=0x%02X\n", config);
    chip.write_register(0x02, 0x04);                            // Write raw register, (reg, value) → bool
                                                            // POLARITY: invert P2

    chip.watchChanges(onChange);                                // Register change callback, (callback) → void
                                                            // no INT pin exists; service() polls and dispatches
    chip.pin(2).watch(onPin, InputPin::kFalling);               // Watch P2 falling edges, (handler, trigger) → bool
                                                            // filters the edge in software; all pins share one poll
    chip.service();                                             // Poll once and dispatch callbacks, () → void
                                                            // reads INPUT; call periodically from the main loop
    uint8_t changed = chip.pollChanges();                       // Read INPUT, get changed mask, () → uint8_t
                                                            // XOR with the last INPUT value
    printk("changed=0x%X\n", changed);
    chip.pin(2).unwatch();                                      // Stop watching P2, () → void
                                                            // drops the handler
    chip.unwatchChanges();                                      // Remove change callback, () → void
                                                            // stops dispatch
    chip.reset();                                               // Restore reset values, () → void
                                                            // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
    while (1) { k_sleep(K_SECONDS(1)); }
    return 0;
}
