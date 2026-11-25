#include <Wire.h>
#include <stdio.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x41);
PCA9536Full chip(connection);                               // Create PCA9536 driver, (connection, addr=0x41)
                                                            // writes the reset values; all pins start as inputs

// Callbacks are plain function pointers (no std::function, no heap).
static void onChange(uint8_t changed) {
    { char b[96]; snprintf(b, sizeof b, "changed=0x%X\n", changed); Serial.print(b); }
}
static void onPin(PCA9536Full::IOExpanderPin*) {
    { char b[96]; snprintf(b, sizeof b, "P2 falling\n"); Serial.print(b); }
}

void setup() {
    Serial.begin(115200);
    Wire.begin();

    chip.configure_polarity(0x00);                              // Normal input polarity, (mask=0x00) → void
                                                            // writes POLARITY; 1 bits invert the INPUT read
    chip.set_direction(0x0C);                                   // P0–P1 outputs, P2–P3 inputs, (mask=0x0C) → void
                                                            // OUTPUT is written before CONFIG (no glitch)
    uint8_t dir = chip.get_direction();                         // Read the direction shadow, () → uint8_t
                                                            // 1 = input, 0 = output
    { char b[96]; snprintf(b, sizeof b, "CONFIG=0x%X\n", dir); Serial.print(b); }

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
    { char b[96]; snprintf(b, sizeof b, "PORT=0x%X\n", port_val); Serial.print(b); }
    uint8_t config = chip.read_register(0x03);                  // Read raw register, (reg) → uint8_t
                                                            // escape hatch for debugging
    { char b[96]; snprintf(b, sizeof b, "CONFIG=0x%02X\n", config); Serial.print(b); }
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
    { char b[96]; snprintf(b, sizeof b, "changed=0x%X\n", changed); Serial.print(b); }
    chip.pin(2).unwatch();                                      // Stop watching P2, () → void
                                                            // drops the handler
    chip.unwatchChanges();                                      // Remove change callback, () → void
                                                            // stops dispatch
    chip.reset();                                               // Restore reset values, () → void
                                                            // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
}

void loop() {}
