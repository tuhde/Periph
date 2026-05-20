#include <Wire.h>
#include "I2CTransport.h"
#include "PCF8574.h"

I2CTransport transport(Wire, 0x20);                            // Create I2C transport, (wire, addr=0x20)
PCF8574Minimal chip(transport);                                // Create PCF8574 driver, (transport, addr=0x20)

PCF8574Minimal::IOExpanderPin p0 = chip.pin(0);               // Get pin proxy, (n) → IOExpanderPin
PCF8574Minimal::IOExpanderPin p4 = chip.pin(4);               // Get pin proxy, (n) → IOExpanderPin

void setup() {
    Serial.begin(115200);
    Wire.begin();
    p0.mode(OUTPUT);                                           // Set direction, (mode=OUTPUT) → void
    p4.mode(INPUT);                                            // Set direction, (mode=INPUT) → void
}

void loop() {
    uint8_t port = chip.read_port();                           // Read all 8 pins, () → uint8_t bitmask
    if ((port >> 4) & 1) p0.high(); else p0.low();            // Set high, () → void / Set low, () → void
    delay(200);
}
