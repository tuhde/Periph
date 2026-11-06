#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x2C);
AD5248Full pot(connection, 10000.0f, 160.0f);                // Create AD5248 driver, (connection, r_ab=10000 Ω, r_wiper=160 Ω)

void setup() {
    Serial.begin(115200);
    Wire.begin();

    // --- Sweep channel 0 across its full range ---
    // Programmable gain / level trim: step the wiper from code 0 to 255 in steps of 16
    // and print the resulting W-B resistance. Zero scale is not 0 Ω (about 2*RW = 320 Ω).
    for (int code = 0; code <= 255; code += 16) {
        pot.set_wiper(0, code);                              // Set channel 0 wiper, (channel 0-1, code 0-255) → void
        float ohms = pot.get_resistance(0);                  // Read W-B resistance, (channel 0-1) → float Ω
        Serial.print("code=");
        Serial.print(code);
        Serial.print(" R_WB ohm=");
        Serial.println((ohms), 1);
        delay(100);
    }
    // --- Set channel 1 to 2.2 kΩ and report the quantisation error ---
    // 256 positions over 10 kΩ give a step of about 39 Ω, so the result is the nearest code.
    pot.set_resistance(1, 2200.0f);                          // Set channel 1 to 2.2 kΩ, (channel 0-1, ohms) → void
    float actual = pot.get_resistance(1);                    // Read back W-B resistance, (channel 0-1) → float Ω
    Serial.print("ch1 actual ohm=");
    Serial.print((actual), 1);
    Serial.print(" error ohm=");
    Serial.println((actual - 2200.0f), 1);
    // --- Shutdown and resume channel 1 ---
    // Shutdown opens terminal A and shorts the wiper to B (about 0 Ω) without changing the RDAC.
    pot.shutdown(1);                                         // Shut channel 1 down, (channel 0-1) → void
    bool down = pot.is_shutdown(1);                          // Query driver-tracked state, (channel 0-1) → bool
    Serial.print("ch1 shutdown=");
    Serial.println(down);
    delay(1000);
    pot.resume(1);                                           // Resume channel 1, (channel 0-1) → void
    // --- Restore the power-up preset ---
    pot.reset();                                             // Reset both channels to midscale, () → void
}

void loop() {
    delay(1000);
}
