#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, MCP3422Minimal::I2C_ADDRESS);
MCP3422Minimal adc(connection);                         // Create MCP3422 driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    for (int i = 0; i < 10; i++) {
        int32_t raw0 = adc.readRaw(0);                  // Read a signed code, (channel 0–1) → int32_t
        float v1 = adc.readVoltage(1);                  // Read the differential voltage, (channel 0–1) → float V
        Serial.print("CH1 raw "); Serial.print((int)raw0); Serial.print(", CH2 "); Serial.print((double)v1, 6); Serial.println(" V");
        float both[2];
        bool ok = adc.readAll(both);                    // Read both channels, (float out[2]) → bool
        Serial.print("all "); Serial.print((int)ok); Serial.print(" "); Serial.print((double)both[0], 6); Serial.print(" "); Serial.println((double)both[1], 6);
        delay(1000);
    }
}

void loop() {
}
