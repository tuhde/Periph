#include <Wire.h>
#include <stdio.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x20);
PCA6408AMinimal chip(connection);                             // Create PCA6408A driver, (connection, addr=0x20)

static PCA6408AMinimal* g_chip = nullptr;

void setup() {
    Serial.begin(115200);
    Wire.begin();

    g_chip = &chip;

    auto p0 = chip.pin(0);                                    // Get P0 proxy, (n) → IOExpanderPin
    auto p7 = chip.pin(7);                                    // Get P7 proxy, (n) → IOExpanderPin

    p7.mode(OUTPUT);                                          // Set pin direction, (m INPUT/OUTPUT) → bool
    p7.high();                                                // Drive P7 high, () → void
    p0.mode(INPUT);                                           // Set pin direction, (m INPUT/OUTPUT) → bool
    uint8_t val = p0.read();                                  // Read pin level, () → uint8_t 0|1

    chip.write_port(0, 0x81);                                 // Write the output latch, (port=0, mask) → void
    uint8_t port_val = chip.read_port();                      // Read all 8 pins, (port=0) → uint8_t bitmask

    { char b[64]; snprintf(b, sizeof b, "PORT=0x%02X  P0=%d\n", port_val, val); Serial.print(b); }
}

void loop() {}
