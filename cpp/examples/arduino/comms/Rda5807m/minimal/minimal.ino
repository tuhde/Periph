#include <Wire.h>
#include "I2CConnection.h"
#include "Rda5807m.h"

I2CConnection connection(Wire, 0x10);
RDA5807MMinimal fm(connection, 100.0f, 8);                // Create RDA5807M driver, (connection, frequency_mhz=100.0, volume=8) → None

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    float freq;
    if (fm.seek(true, freq)) {                            // Seek to next station, (up=true, frequency_mhz) → bool
        Serial.println(freq);
    }
    delay(3000);
}
