#include <Wire.h>
#include <stdio.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x20);
PCA6408AFull chip(connection);                                // Create PCA6408A driver, (connection, addr=0x20)

static PCA6408AFull* g_chip = nullptr;

// Callbacks are plain function pointers (no std::function, no heap).
static void onChange(uint8_t changed) {
    { char b[64]; snprintf(b, sizeof b, "interrupt, changed=0x%02X\n", changed); Serial.print(b); }
}
static void onPin(PCA6408AFull::IOExpanderPin*) {
    { char b[64]; snprintf(b, sizeof b, "P4 falling\n"); Serial.print(b); }
}

void setup() {
    Serial.begin(115200);
    Wire.begin();

    g_chip = &chip;

    chip.configure_polarity(0x00);                            // Normal input polarity, (mask=0x00) → void
                                                              // writes POLARITY; 1 bits invert the INPUT read
    chip.set_direction(0xF0);                                 // P0–P3 outputs, P4–P7 inputs, (mask=0xF0) → void
                                                              // OUTPUT is written before CONFIG (no glitch)
    uint8_t dir = chip.get_direction();                       // Read the direction shadow, () → uint8_t
                                                              // 1 = input, 0 = output
    { char b[64]; snprintf(b, sizeof b, "CONFIG=0x%02X\n", dir); Serial.print(b); }

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

    chip.write_port(0, 0x05);                                 // Write the output latch, (port=0, mask) → void
                                                              // whole-port OUTPUT write, updates the shadow
    uint8_t port_val = chip.read_port();                      // Read all 8 pins, (port=0) → uint8_t bitmask
                                                              // reads INPUT (actual pin levels)
    { char b[64]; snprintf(b, sizeof b, "PORT=0x%02X\n", port_val); Serial.print(b); }
    uint8_t config = chip.read_register(0x03);                // Read raw register, (reg) → uint8_t
                                                              // escape hatch for debugging
    { char b[64]; snprintf(b, sizeof b, "CONFIG=0x%02X\n", config); Serial.print(b); }
    chip.write_register(0x02, 0x10);                          // Write raw register, (reg, value) → bool
                                                              // POLARITY: invert P4

    chip.onInterrupt(onChange);                               // Subscribe to INT, (callback, intPin=nullptr) → void
                                                              // uses connection.intPin() if wired; otherwise call service()
    chip.pin(4).watch(onPin, InputPin::kFalling);             // Watch P4 falling edges, (handler, trigger) → bool
                                                              // filters the edge in software; all pins share the INT line
    chip.service();                                           // Poll once and dispatch callbacks, () → void
                                                              // reads INPUT; use when no INT pin is wired
    uint8_t changed = chip.pollInterrupt();                   // Read INPUT, get changed mask, () → uint8_t
                                                              // XOR with the last INPUT value
    { char b[64]; snprintf(b, sizeof b, "changed=0x%02X\n", changed); Serial.print(b); }
    chip.pin(4).unwatch();                                    // Stop watching P4, () → void
                                                              // drops the handler
    chip.offInterrupt();                                      // Unsubscribe, () → void
                                                              // stops delivery
    chip.reset();                                             // Restore reset values, () → void
                                                              // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
}

void loop() {}
