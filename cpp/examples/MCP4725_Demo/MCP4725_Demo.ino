#include <Wire.h>
#include "I2CTransport.h"
#include "MCP4725.h"
#include <math.h>

I2CTransport transport(Wire, 0x60);
MCP4725Full dac(transport);

const float STEP = 1.0f / 20.0f;
const unsigned long DELAY_MS = 100;

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    for (int n = 0; n <= 20; n++) {
        float fraction = n * STEP;
        dac.set_voltage(fraction);
        uint16_t code = (uint16_t)(fraction * 4095.0f + 0.5f);
        float approx_v = code * 3.3f / 4096.0f;
        Serial.print("n=");
        Serial.print(n);
        Serial.print(" fraction=");
        Serial.print(fraction);
        Serial.print(" code=");
        Serial.print(code);
        Serial.print(" approx_v=");
        Serial.print(approx_v, 3);
        Serial.println("V");
        delay(DELAY_MS);
    }
    for (int n = 20; n >= 0; n--) {
        float fraction = n * STEP;
        dac.set_voltage(fraction);
        uint16_t code = (uint16_t)(fraction * 4095.0f + 0.5f);
        float approx_v = code * 3.3f / 4096.0f;
        Serial.print("n=");
        Serial.print(n);
        Serial.print(" fraction=");
        Serial.print(fraction);
        Serial.print(" code=");
        Serial.print(code);
        Serial.print(" approx_v=");
        Serial.print(approx_v, 3);
        Serial.println("V");
        delay(DELAY_MS);
    }
}