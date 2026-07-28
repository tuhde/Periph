#include <Wire.h>
#include "I2CConnection.h"
#include "InputPinArduino.h"
#include "MCP23017.h"

InputPinArduino intPin(5);                                       // Create INT pin, (pin=5)
I2CConnection connection(Wire, 0x20, &intPin);                   // Create I2C connection, (wire, addr=0x20, intPin)
MCP23017Full mcp(connection);

void setup() {
    Serial.begin(115200);
    Wire.begin();

    mcp.configure_pullup(0, 0x3F);                          // Enable pull-ups on GPA0–GPA5, (port=0, mask=0x3F) → None
    mcp.configure_polarity(0, 0x00);                        // Set normal polarity PORTA, (port=0, mask=0x00) → None

    auto p7 = mcp.pin(7);                                    // GPA7 is output-only; get as output
    p7.mode(OUTPUT);                                        // Set pin direction, (mode=OUTPUT) → None
    p7.low();                                                // Drive GPA7 low, () → None

    for (uint8_t n = 0; n < 8; n++) {
        auto p = mcp.pin(n);                                 // Get pin n as input, (n) → IOExpanderPin
        p.mode(INPUT);                                       // Set pin direction, (mode=INPUT) → None
        uint8_t val = p.read();                              // Read pin level, () → uint8_t 0|1
        Serial.print("GPA");
        Serial.print(n);
        Serial.print("=");
        Serial.print(val);
        Serial.print("  ");
    }
    Serial.println();

    mcp.write_port(0, 0x80);                               // Write GPA7 high, (port=0, mask=0x80) → None

    mcp.set_default_value(0, 0x00);                        // Set DEFVAL for PORTA, (port=0, mask=0x00) → None

    mcp.onInterrupt([](uint8_t port, uint8_t status) {      // Subscribe to INT on both ports, (callback, intPin, mirror) → None
        Serial.print("port ");
        Serial.print(port);
        Serial.print(" changed: ");
        Serial.println(status, HEX);
    }, &intPin, /*mirror=*/true);

    mcp.offInterrupt(0);                                    // Unsubscribe PORTA, (port=0) → None

    uint8_t porta = mcp.read_port(0);                       // Read PORTA, (port=0) → uint8_t
    uint8_t portb = mcp.read_port(1);                      // Read PORTB, (port=1) → uint8_t
    Serial.print("PORTA=");
    Serial.print(porta, HEX);
    Serial.print("  PORTB=");
    Serial.println(portb, HEX);
}

void loop() {}