#include <Wire.h>
#include "I2CConnection.h"
#include "InputPinArduino.h"
#include "PCF8575.h"

InputPinArduino intPin(5);                                       // Create INT pin, (pin=5)
I2CConnection connection(Wire, 0x20, &intPin);                   // Create I2C connection, (wire, addr=0x20, intPin)
PCF8575Full chip(connection);                                   // Create PCF8575 full driver, (connection, addr=0x20)

void setup() {
    Serial.begin(115200);
    Wire.begin();
    // Configure output pins for LEDs (P00–P07 active-low)
    chip.write_port(0, 0xFF);                                   // Write Port 0, (port=0, mask=uint8_t) → void
    chip.write_port(1, 0xFF);                                   // Write Port 1, (port=1, mask=uint8_t) → void

    chip.onInterrupt([](uint16_t changed) {
        (void)changed;
    });                                                          // Subscribe to INT line, (callback) → void
}

void loop() {
    uint8_t port0 = chip.read_port(0);                          // Read Port 0, (port=0) → uint8_t bitmask
    uint8_t port1 = chip.read_port(1);                         // Read Port 1, (port=1) → uint8_t bitmask

    uint8_t buttons = port1;                                    // P10–P17 (pressed = 0)
    uint8_t led_bits = ~buttons;                                // invert: pressed → LED on (0)
    chip.write_port(0, led_bits);                               // Write Port 0, (port=0, mask=uint8_t) → void

    Serial.print("P0=0x"); Serial.print(port0, HEX);
    Serial.print("  P1=0x"); Serial.print(port1, HEX);
    Serial.print("  buttons=");
    for (int i = 7; i >= 0; i--) Serial.print((buttons >> i) & 1 ? '.' : 'X');
    Serial.print("  LEDs=");
    for (int i = 7; i >= 0; i--) Serial.print((led_bits >> i) & 1 ? ' ' : '*');
    Serial.println();
    delay(200);
}