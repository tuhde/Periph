#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, AD5243Minimal::I2C_ADDRESS);
AD5243Minimal pot(connection);                          // Create AD5243 driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    static const int codes[] = {0, 64, 128, 192, 255};
    for (int i = 0; i < 5; i++) {
        pot.setWiper(0, codes[i]);                      // Set wiper position, (channel 0–1, code 0–255) → bool
        uint8_t w = pot.getWiper(0);                    // Read wiper position, (channel 0–1) → uint8_t 0–255
        float r = pot.getResistance(0);                 // Read W–B resistance, (channel 0–1) → float Ω
        Serial.print("code "); Serial.print(codes[i]); Serial.print(" read "); Serial.print((int)w); Serial.print(" -> "); Serial.print((double)r, 1); Serial.println(" ohm");
        delay(500);
    }
    pot.setResistance(1, 2200.0f);                      // Set W–B resistance, (channel 0–1, ohms Ω) → bool
}

void loop() {
}
