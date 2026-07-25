#include <Wire.h>
#include "I2CTransport.h"
#include "INA3221.h"

I2CTransport transport(Wire, 0x40);
INA3221Minimal ina(transport);                        // Create INA3221 driver, (transport, r_shunt=0.1 Ω)

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    for (uint8_t ch = 1; ch <= 3; ch++) {
        Serial.print(ina.voltage(ch));   Serial.print("V  ");  // Read bus voltage, (channel) → V
        Serial.print(ina.current(ch));   Serial.print("A  ");  // Read load current, (channel) → A
        Serial.print(ina.power(ch));     Serial.print("W  ");  // Read power, (channel) → W
    }
    Serial.println();
    delay(1000);
}