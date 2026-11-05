#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x2C);
AD5248Minimal pot(connection, 10000.0f, 160.0f);             // Create AD5248 driver, (connection, r_ab=10000 Ω, r_wiper=160 Ω)

void setup() {
    Serial.begin(115200);
    Wire.begin();

    pot.set_wiper(0, 64);                                    // Set channel 0 wiper, (channel 0-1, code 0-255) → void
    pot.set_resistance(1, 2200.0f);                          // Set channel 1 W-B resistance, (channel 0-1, ohms) → void
}

void loop() {
    uint8_t code = pot.get_wiper(0);                         // Read channel 0 wiper, (channel 0-1) → uint8_t 0-255
    float ohms = pot.get_resistance(0);                      // Read channel 0 W-B resistance, (channel 0-1) → float Ω
    Serial.print("ch0 code=");
    Serial.print(code);
    Serial.print(" R_WB ohm=");
    Serial.println((ohms), 1);
    delay(1000);
}
