#include <Wire.h>
#include "I2CTransport.h"
#include "MCP4728.h"

I2CTransport transport(Wire, 0x60);
MCP4728Full dac(transport);

const float STEP = 1.0f / 16.0f;
const unsigned long DELAY_MS = 50;

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    // Apply four-point calibration voltages to channels A–D
    float calibration[4] = {0.0f, 1.0f / 3.0f, 2.0f / 3.0f, 1.0f};
    dac.set_all(calibration);
    for (uint8_t ch = 0; ch < 4; ch++) {
        uint16_t code = (uint16_t)(calibration[ch] * 4095.0f + 0.5f);
        Serial.print("ch=");
        Serial.print(ch);
        Serial.print(" fraction=");
        Serial.print(calibration[ch], 4);
        Serial.print(" code=");
        Serial.print(code);
        Serial.print(" approx_v=");
        Serial.print(code * 3.3f / 4096.0f, 3);
        Serial.println("V");
    }
    delay(500);

    // Synchronous staircase from 0 to full scale on all four channels
    for (int n = 0; n <= 16; n++) {
        float f = n * STEP;
        float fractions[4] = {f, f, f, f};
        dac.set_all(fractions);
        uint16_t code = (uint16_t)(f * 4095.0f + 0.5f);
        Serial.print("step=");
        Serial.print(n);
        Serial.print(" fraction=");
        Serial.print(f, 4);
        Serial.print(" code=");
        Serial.print(code);
        Serial.print(" approx_v=");
        Serial.print(code * 3.3f / 4096.0f, 3);
        Serial.println("V");
        delay(DELAY_MS);
    }

    // Reset all channels to 0 V before next loop iteration
    float zero[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    dac.set_all(zero);
}
