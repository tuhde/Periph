#include <Wire.h>
#include "I2CTransport.h"
#include "PCF8575.h"

I2CTransport transport(Wire, 0x20);                            // Create I2C transport, (wire, addr=0x20)
PCF8575Minimal chip(transport);                                 // Create PCF8575 driver, (transport, addr=0x20)

PCF8575Minimal::IOExpanderPin p0 = chip.pin(0);                // Get pin proxy, (n=0) → IOExpanderPin
PCF8575Minimal::IOExpanderPin p8 = chip.pin(8);                // Get pin proxy, (n=8) → IOExpanderPin

void setup() {
    Serial.begin(115200);
    Wire.begin();
    p0.mode(OUTPUT);                                            // Set direction, (mode=OUTPUT) → void
    p8.mode(INPUT);                                             // Set direction, (mode=INPUT) → void
}

void loop() {
    uint8_t port0 = chip.read_port(0);                          // Read Port 0, (port=0) → uint8_t bitmask
    uint8_t port1 = chip.read_port(1);                           // Read Port 1, (port=1) → uint8_t bitmask
    if ((port1 >> 0) & 1) p0.high(); else p0.low();             // Set high, () → void / Set low, () → void
    delay(200);
}