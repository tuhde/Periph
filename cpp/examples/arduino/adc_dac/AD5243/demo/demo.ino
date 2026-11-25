#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, AD5243Full::I2C_ADDRESS);
AD5243Full pot(connection);                             // Create AD5243 Full driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    const float VDD = 3.3f;

    // --- Step channel 0 through divider ratios ---
    // With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
    // round(ratio × 256), so 0.999 lands on the top code 255.
    static const float ratios[] = {0.0f, 0.25f, 0.5f, 0.75f, 0.999f};
    for (int i = 0; i < 5; i++) {
        pot.setRatio(0, ratios[i]);                     // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → bool
        uint8_t code = pot.getWiper(0);                 // Read wiper position, (channel 0–1) → uint8_t 0–255
        float actual = pot.getRatio(0);                 // Read divider ratio, (channel 0–1) → float
        Serial.print("ratio "); Serial.print((double)ratios[i], 3); Serial.print(" -> code "); Serial.print((int)code); Serial.print(" ("); Serial.print((double)actual, 4); Serial.print(") -> "); Serial.print((double)(actual * VDD), 3); Serial.println(" V");
        delay(500);
    }

    // --- Use channel 1 as a 2.2 kΩ rheostat ---
    // The 256 steps quantise the resistance (~39 Ω per code on a 10 kΩ part), so
    // the readback differs slightly from the request.
    pot.setResistance(1, 2200.0f);                      // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    float r = pot.getResistance(1);                     // Read W–B resistance, (channel 0–1) → float Ω
    Serial.print("rheostat "); Serial.print((double)r, 1); Serial.print(" ohm, error "); Serial.print((double)(r - 2200.0f), 1); Serial.println(" ohm");

    // --- Shut channel 1 down and bring it back ---
    // Shutdown opens A and shorts the wiper to B (about 0 Ω) while the RDAC keeps
    // its value, so resume restores the 2.2 kΩ setting.
    pot.shutdown(1);                                    // Shut channel down, (channel 0–1) → void
    bool sd = pot.isShutdown(1);                        // Query shutdown state, (channel 0–1) → bool
    Serial.print("shutdown "); Serial.println((int)sd);
    delay(1000);
    pot.resume(1);                                      // Leave shutdown, (channel 0–1) → void

    // --- Restore the power-up state ---
    // The chip has no reset pin, so reset() rewrites midscale to both channels.
    pot.reset();                                        // Restore power-up preset, () → void
}

void loop() {
}
