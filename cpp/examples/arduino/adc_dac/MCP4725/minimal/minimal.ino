#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x60);
MCP4725Minimal dac(connection);

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    dac.set_voltage(0.5);
    dac.set_raw(2048);
    delay(1000);
}
