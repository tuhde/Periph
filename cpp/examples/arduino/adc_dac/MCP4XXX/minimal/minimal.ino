#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x58);
MCP4XXXMinimal pot(connection, MCP4XXXPart::MCP4532, 10000.0f, 75.0f); // Create MCP4XXX driver, (connection, part, r_ab=10000 Ω, r_wiper=75 Ω)

void setup() {
    Serial.begin(115200);
    Wire.begin();
    pot.init();                                              // Bus check, () → void

    pot.set_wiper(32, 0);                                    // Set wiper, (code 0-128, channel=0) → void
    pot.set_resistance(2200.0f, 0); // Set W-B resistance, (ohms, channel=0) → void
}

void loop() {
    uint16_t code = pot.get_wiper(0); // Read wiper, (channel=0) → uint16_t 0-128
    float ohms = pot.get_resistance(0); // Read W-B resistance, (channel=0) → float Ω
    Serial.print("code=");
    Serial.print(code);
    Serial.print(" R_WB ohm=");
    Serial.println(ohms, 1);
    delay(1000);
}
