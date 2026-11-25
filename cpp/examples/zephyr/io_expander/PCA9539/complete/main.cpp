#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "PCA9539.h"

#ifndef PCA9539_I2C_NODE
#define PCA9539_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef PCA9539_ADDR
#define PCA9539_ADDR 0x74
#endif

static PCA9539Full* g_chip = nullptr;

// Callbacks are plain function pointers (no std::function, no heap).
static void onChange(uint16_t changed) {
    printk("interrupt, changed=0x%04X\n", changed);
}
static void onPin(PCA9539Full::IOExpanderPin*) {
    printk("P14 falling\n");
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(PCA9539_I2C_NODE);
    I2CConnectionZephyr connection(dev, PCA9539_ADDR);

    PCA9539Full chip(connection);                            // Create PCA9539 driver, (connection, addr=0x74)
                                                              // writes the reset values; all pins start as inputs
    g_chip = &chip;

    chip.configure_polarity(0x0000);                            // Normal input polarity, (mask=0x0000) → void
                                                              // writes POLARITY; 1 bits invert the INPUT read
    chip.set_direction(0xFF00);                                 // Port 0 outputs, port 1 inputs, (mask=0xFF00) → void
                                                              // OUTPUT is written before CONFIG (no glitch)
    uint16_t dir = chip.get_direction();                       // Read the direction shadow, () → uint16_t
                                                              // 1 = input, 0 = output
    printk("CONFIG=0x%04X\n", dir);

    for (uint8_t n = 0; n < 4; n++) {
        auto p = chip.pin(n);                                 // Get LED pin proxy, (n) → IOExpanderPin
                                                              // Full proxy with watch support
        p.mode(OUTPUT);                                       // Set pin direction, (m INPUT/OUTPUT) → bool
                                                              // latches OUTPUT, then clears the CONFIG bit
        p.low();                                              // Drive pin low, () → void
                                                              // clears the OUTPUT bit via the shadow
        p.toggle();                                           // Invert the output latch, () → void
                                                              // one OUTPUT write, no read-modify-write
    }

    chip.write_port(0, 0x05);                                 // Write port 0 output latch, (port, mask) → void
                                                              // single-port OUTPUT write, updates the shadow
    chip.write_all(0xA505);                                    // Write both output latches, (value) → void
                                                               // one two-byte write at OUTPUT0 (register-pair toggling)
    uint8_t port_val = chip.read_port(1);                      // Read port 1 pins, (port) → uint8_t bitmask
                                                              // reads INPUT (actual pin levels)
    printk("PORT1=0x%02X\n", port_val);
    uint16_t all = chip.read_all();                            // Read all 16 pins, () → uint16_t
                                                               // one two-byte read at INPUT0
    printk("ALL=0x%04X\n", all);
    uint8_t config = chip.read_register(0x07);                // Read raw register, (reg) → uint8_t
                                                              // escape hatch for debugging
    printk("CONFIG1=0x%02X\n", config);
    chip.write_register(0x05, 0x10);                          // Write raw register, (reg, value) → bool
                                                              // POLARITY1: invert P14

    chip.onInterrupt(onChange);                               // Subscribe to INT, (callback, intPin=nullptr) → void
                                                              // uses connection.intPin() if wired; otherwise call service()
    chip.pin(12).watch(onPin, InputPin::kFalling);             // Watch P14 falling edges, (handler, trigger) → bool
                                                              // filters the edge in software; all pins share the INT line
    chip.service();                                           // Poll once and dispatch callbacks, () → void
                                                              // reads INPUT; use when no INT pin is wired
    uint16_t changed = chip.pollInterrupt();                   // Read INPUT0/1, get changed mask, () → uint16_t
                                                              // XOR with the last INPUT values
    printk("changed=0x%04X\n", changed);
    chip.pin(12).unwatch();                                    // Stop watching P14, () → void
                                                              // drops the handler
    chip.offInterrupt();                                      // Unsubscribe, () → void
                                                              // stops delivery
    chip.reset();                                             // Restore reset values, () → void
                                                              // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF (both ports)
    return 0;
}
