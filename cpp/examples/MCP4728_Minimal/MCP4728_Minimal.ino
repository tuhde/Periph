#include <Wire.h>
#include "I2CTransport.h"
#include "MCP4728.h"

I2CTransport transport(Wire, 0x60);
MCP4728Minimal dac(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    dac.set_voltage(0, 0.5f);
    dac.set_raw(1, 2048);
    float fractions[4] = {0.0f, 0.25f, 0.5f, 1.0f};
    dac.set_all(fractions);
    delay(1000);
}
