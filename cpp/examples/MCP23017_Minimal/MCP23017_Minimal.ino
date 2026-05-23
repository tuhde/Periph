#include <Wire.h>
#include "I2CTransport.h"
#include "MCP23017.h"

I2CTransport transport(Wire, 0x20);
MCP23017Minimal mcp(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();

    auto p0 = mcp.pin(0);                                     // Get pin 0, () → IOExpanderPin
    auto p7 = mcp.pin(7);                                     // Get pin 7 (GPA7 output-only), () → IOExpanderPin

    p7.mode(OUTPUT);                                         // Set pin direction, (mode=OUTPUT) → None
    p7.low();                                                // Drive pin 7 low, () → None

    p0.mode(INPUT);                                          // Set pin direction, (mode=INPUT) → None
    uint8_t val = p0.read();                                 // Read pin level, () → uint8_t 0|1

    mcp.write_port(0, 0x01);                                 // Write PORTA mask, (port=0, mask) → None
    uint8_t port_val = mcp.read_port(0);                   // Read PORTA, (port=0) → uint8_t 0–255

    Serial.print("PORTA=");
    Serial.print(port_val, HEX);
    Serial.print("  pin0=");
    Serial.println(val);
}

void loop() {}