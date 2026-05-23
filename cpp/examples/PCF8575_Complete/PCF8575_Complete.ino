#include <Wire.h>
#include "I2CTransport.h"
#include "PCF8575.h"

I2CTransport transport(Wire, 0x20);                            // Create I2C transport, (wire, addr=0x20)
PCF8575Full chip(transport);                                   // Create PCF8575 full driver, (transport, addr=0x20)

PCF8575Full::IOExpanderPin p0 = chip.pin(0);                   // Get pin proxy, (n=0) → IOExpanderPin
PCF8575Full::IOExpanderPin p8 = chip.pin(8);                   // Get pin proxy, (n=8) → IOExpanderPin

void setup() {
    Serial.begin(115200);
    Wire.begin();
    p0.mode(OUTPUT);                                            // Set direction, (mode=OUTPUT) → void
                                                               // drives P00 low (safe initial state for output)
    p0.high();                                                  // Set high, () → void
                                                               // releases to quasi-input; not strong drive
    p0.low();                                                   // Drive low, () → void
                                                               // strong pull-down, up to 25 mA
    p0.toggle();                                                // Invert shadow bit, () → void

    uint8_t v = p0.read();                                      // Read actual level, () → uint8_t

    chip.write_port(0, 0b00001111);                             // Write Port 0, (port=0, mask=uint8_t) → void
    chip.write_port(1, 0b00001111);                             // Write Port 1, (port=1, mask=uint8_t) → void

    p8.mode(INPUT);                                             // Set direction, (mode=INPUT) → void
    uint8_t state = p8.read();                                  // Read actual level, () → uint8_t

    chip.configure_interrupt(5, [](uint8_t changed) {
        Serial.print("changed: ");
        Serial.println(changed, BIN);
    });                                                          // Attach interrupt, (int_gpio_pin, callback) → void

    uint16_t changed = chip.clear_interrupt();                   // Read port and return 16-bit changed bitmask, () → uint16_t
}

void loop() {}