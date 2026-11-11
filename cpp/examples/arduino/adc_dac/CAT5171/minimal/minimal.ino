#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, CAT5171Minimal::I2C_ADDRESS);
CAT5171Minimal pot(connection);                         // Create CAT5171 driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    static const int codes[] = {0, 64, 128, 192, 255};
    for (int i = 0; i < 5; i++) {
        pot.setWiper(codes[i]);                         // Set wiper position, (code 0–255) → void
        uint8_t w = pot.getWiper();                     // Read wiper position, () → uint8_t 0–255
        float r = pot.getResistance();                  // Read W–B resistance, () → float Ω
        Serial.print("code "); Serial.print(codes[i]); Serial.print(" read "); Serial.print((int)w); Serial.print(" -> "); Serial.print((double)r, 1); Serial.println(" ohm");
        delay(500);
    }
    pot.setResistance(22000.0f);                        // Set W–B resistance, (ohms Ω) → void
}

void loop() {
}
