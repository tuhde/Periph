#include <Wire.h>
#include "I2CConnection.h"
#include "PCF8591.h"

I2CConnection connection(Wire, 0x48);
PCF8591Minimal adc(connection);

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    uint8_t ch0 = adc.read_channel(0);                  // Read single channel, (channel=0–3) → uint8_t
    uint8_t raw[PCF8591Minimal::NUM_CHANNELS];
    adc.read_all(raw);                                  // Read all four channels, (out[4]) → None
    delay(1000);
}
