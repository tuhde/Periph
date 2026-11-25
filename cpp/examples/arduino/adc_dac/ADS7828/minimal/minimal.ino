#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x48);
ADS7828Minimal adc(connection);

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    uint16_t raw = adc.readRaw(0);                          // Read CH0 raw code, (channel=0–7) → uint16_t
    float v0 = adc.readVoltage(0);                          // Read CH0 voltage, (channel=0–7) → float V
    float all[8];
    adc.readAll(all);                                       // Read all eight channels, (out[8]) → void
    Serial.print("raw="); Serial.print(raw);
    Serial.print(" v0="); Serial.println(v0, 3);
    delay(1000);
}
