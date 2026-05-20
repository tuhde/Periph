#include <Wire.h>
#include "I2CTransport.h"
#include "PCF8574.h"

I2CTransport transport(Wire, 0x20);                            // Create I2C transport, (wire, addr=0x20)
PCF8574Full chip(transport);                                    // Create PCF8574 full driver, (transport, addr=0x20)
                                                               // initialises all pins as inputs; shadow = 0xFF

void setup() {
    Serial.begin(115200);
    Wire.begin();

    PCF8574Full::IOExpanderPin p0 = chip.pin(0);               // Get full pin proxy, (n) → IOExpanderPin
                                                               // returned by value; holds reference to chip
    p0.mode(OUTPUT);                                           // Set direction output, (mode=OUTPUT) → void
                                                               // drives P0 low (initial state for output)
    p0.high();                                                 // Set high (release to quasi-input), () → void
                                                               // shadow |= (1 << 0); writes full shadow byte
    p0.low();                                                  // Drive low, () → void
                                                               // shadow &= ~(1 << 0); writes full shadow byte
    p0.toggle();                                               // Invert shadow bit, () → void
                                                               // reads actual pin then flips shadow
    p0.write(HIGH);                                            // Write pin, (v=HIGH|LOW) → void
                                                               // equivalent to high()
    uint8_t v = p0.read();                                     // Read actual level, () → uint8_t
                                                               // reads full port byte, returns bit n
    Serial.println(v);

    uint8_t mask = chip.read_port();                           // Read all 8 pins, (port=0) → uint8_t bitmask
                                                               // P0 in bit 0, P7 in bit 7
    chip.write_port(0, 0b00001111);                            // Write all 8 pins, (port, mask) → void
                                                               // P0–P3 low (outputs), P4–P7 high (inputs)

    PCF8574Full::IOExpanderPin p4 = chip.pin(4);               // Get full pin proxy, (n) → IOExpanderPin
    p4.mode(INPUT);                                            // Set direction input, (mode=INPUT) → void
                                                               // releases pin to quasi-input (shadow bit = 1)
    uint8_t state = p4.read();                                 // Read actual level, () → uint8_t
                                                               // 0 if button pulls P4 low, 1 if floating
    Serial.println(state);

    chip.configure_interrupt(5, [](uint8_t changed) {         // Attach interrupt, (gpio_pin, callback) → void
        Serial.print("INT changed=0x");                        // callback fires on any input change
        Serial.println(changed, HEX);
    });

    uint8_t changed = chip.clear_interrupt();                  // Read and return changed bitmask, () → uint8_t
                                                               // compares current byte to previous; clears INT
    Serial.println(changed, HEX);

    PCF8574Full::IOExpanderPin p5 = chip.pin(5);              // Get full pin proxy, (n) → IOExpanderPin
    p5.mode(INPUT);
    p5.attachInterrupt([](PCF8574Full::IOExpanderPin* p) {    // Attach per-pin interrupt, (handler, mode) → void
        Serial.println("P5 fell");                             // fires when P5 transitions to match mode
    }, FALLING);
    p5.detachInterrupt();                                      // Remove per-pin handler, () → void
}

void loop() {
    delay(200);
}
