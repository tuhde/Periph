#include <Wire.h>
#include <stdio.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x41);
PCA9536Minimal chip(connection);                            // Create PCA9536 driver, (connection, addr=0x41)

void setup() {
    Serial.begin(115200);
    Wire.begin();

    auto p2 = chip.pin(2);                                      // Get P2 proxy, (n) → IOExpanderPin
    auto p0 = chip.pin(0);                                      // Get P0 proxy, (n) → IOExpanderPin

    p0.mode(OUTPUT);                                            // Set pin direction, (m INPUT/OUTPUT) → bool
    p0.high();                                                  // Drive P0 high, () → void
    p2.mode(INPUT);                                             // Set pin direction, (m INPUT/OUTPUT) → bool
    uint8_t val = p2.read();                                    // Read pin level, () → uint8_t 0|1

    chip.write_port(0, 0x09);                                   // Write the output latch, (port=0, mask) → void
    uint8_t port_val = chip.read_port();                        // Read all 4 pins, (port=0) → uint8_t bitmask

    { char b[96]; snprintf(b, sizeof b, "PORT=0x%X  P2=%d\n", port_val, val); Serial.print(b); }
}

void loop() {}
