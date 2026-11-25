#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCA6416A.h"

static PCA6416AFull* g_chip = nullptr;

// Callbacks are plain function pointers (no std::function, no heap).
static void onChange(uint16_t changed) {
    printf("interrupt, changed=0x%04X\n", changed);
}
static void onPin(PCA6416AFull::IOExpanderPin*) {
    printf("P1_4 falling\n");
}

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x20;
    I2CConnectionLinux connection(bus, addr);

    PCA6416AFull chip(connection);                            // Create PCA6416A driver, (connection, addr=0x20)
                                                              // writes the reset values; all pins start as inputs
    g_chip = &chip;

    chip.configure_polarity(0x0000);                            // Normal input polarity, (mask=0x0000) → void
                                                              // writes POLARITY; 1 bits invert the INPUT read
    chip.set_direction(0xFF00);                                 // Port 0 outputs, port 1 inputs, (mask=0xFF00) → void
                                                              // OUTPUT is written before CONFIG (no glitch)
    uint16_t dir = chip.get_direction();                       // Read the direction shadow, () → uint16_t
                                                              // 1 = input, 0 = output
    printf("CONFIG=0x%04X\n", dir);

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
    printf("PORT1=0x%02X\n", port_val);
    uint16_t all = chip.read_all();                            // Read all 16 pins, () → uint16_t
                                                               // one two-byte read at INPUT0
    printf("ALL=0x%04X\n", all);
    uint8_t config = chip.read_register(0x07);                // Read raw register, (reg) → uint8_t
                                                              // escape hatch for debugging
    printf("CONFIG1=0x%02X\n", config);
    chip.write_register(0x05, 0x10);                          // Write raw register, (reg, value) → bool
                                                              // POLARITY1: invert P1_4

    chip.onInterrupt(onChange);                               // Subscribe to INT, (callback, intPin=nullptr) → void
                                                              // uses connection.intPin() if wired; otherwise call service()
    chip.pin(12).watch(onPin, InputPin::kFalling);             // Watch P1_4 falling edges, (handler, trigger) → bool
                                                              // filters the edge in software; all pins share the INT line
    chip.service();                                           // Poll once and dispatch callbacks, () → void
                                                              // reads INPUT; use when no INT pin is wired
    uint16_t changed = chip.pollInterrupt();                   // Read INPUT0/1, get changed mask, () → uint16_t
                                                              // XOR with the last INPUT values
    printf("changed=0x%04X\n", changed);
    chip.pin(12).unwatch();                                    // Stop watching P1_4, () → void
                                                              // drops the handler
    chip.offInterrupt();                                      // Unsubscribe, () → void
                                                              // stops delivery
    chip.reset();                                             // Restore reset values, () → void
                                                              // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF (both ports)
    return 0;
}
