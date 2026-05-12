#include <Wire.h>
#include "I2CTransport.h"
#include "INA3221.h"

I2CTransport transport(Wire, 0x40);
INA3221Full ina(transport);                           // Create INA3221 driver, (transport, r_shunt=0.1 Ω)

void setup() {
    Serial.begin(115200);
    Wire.begin();

    // --- Monitor three rails simultaneously ---
    // User wires CH1 to 5V rail, CH2 to 3.3V rail, CH3 to 12V rail.
    // The demo prints a one-line tabular update each second for 30 seconds.
    Serial.println("V1       I1       P1       V2       I2       P2       V3       I3       P3");
    for (int t = 0; t < 30; t++) {
        for (uint8_t ch = 1; ch <= 3; ch++) {
            float v = ina.voltage(ch);                // Read bus voltage, (channel) → float V
            float i = ina.current(ch);                // Read load current, (channel) → float A
            float p = ina.power(ch);                  // Read power, (channel) → float W
            Serial.print(v, 3); Serial.print(" ");
            Serial.print(i, 4); Serial.print(" ");
            Serial.print(p, 4); Serial.print("   ");
        }
        Serial.println();

        if (t == 9) {
            // --- Arm critical-alert limits at 1.5x current draw ---
            for (uint8_t ch = 1; ch <= 3; ch++) {
                float i = ina.current(ch);
                ina.set_critical_alert(ch, i * 1.5f);
            }
            Serial.println("alerts armed");
        }

        if (t == 19) {
            // --- Arm shunt-voltage summation across all three channels ---
            uint8_t channels[] = {1, 2, 3};
            ina.set_summation_channels(channels, 3, 0.3f);  // Set summation channels, (channels, n, limit_v) → None
                                                             // configures SCC bits and sum limit register
            Serial.println("summation armed");
        }

        delay(1000);
    }

    // --- Dump alert flags and decode any that fired ---
    uint16_t flags = ina.alert_flags();               // Read alert flags, () → int
                                                       // reads Mask/Enable register, clears latched flags
    Serial.print("Mask/Enable: 0x");
    Serial.println(flags, HEX);
}

void loop() {}