#include <Wire.h>
#include "I2CTransport.h"
#include "MCP4725.h"

I2CTransport transport(Wire, 0x60);
MCP4725Minimal dac(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();
    dac.set_voltage(0.5);
}

void loop() {
    delay(1000);
}