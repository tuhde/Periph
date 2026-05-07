#include <Wire.h>
#include "I2CTransport.h"
#include "MCP4725.h"

I2CTransport transport(Wire, 0x60);
MCP4725Full dac(transport);

float step = 1.0 / 20.0;

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    for (int i = 0; i <= 20; i++) {
        float fraction = i * step;
        dac.set_voltage(fraction);
        float voltage = fraction * 3.3;
        Serial.print(fraction, 2);
        Serial.print(" -> ");
        Serial.print(voltage, 3);
        Serial.println(" V");
        delay(100);
    }
    for (int i = 20; i >= 0; i--) {
        float fraction = i * step;
        dac.set_voltage(fraction);
        float voltage = fraction * 3.3;
        Serial.print(fraction, 2);
        Serial.print(" -> ");
        Serial.print(voltage, 3);
        Serial.println(" V");
        delay(100);
    }
}