#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, Si1132Minimal::I2C_ADDRESS);
Si1132Minimal sensor(connection);                               // Create Si1132 driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    sensor.init();                                                  // Initialize the chip, () → bool
    for (int i = 0; i < 10; i++) {
        float uv = 0; uint16_t vis = 0, ir = 0;
        sensor.read(uv, vis, ir);                                   // Read UV index and ALS counts, (uv&, visible&, infrared&) → bool
        Serial.print("UV "); Serial.print(uv, 2); Serial.print(" visible "); Serial.print((unsigned long)(vis)); Serial.print(" infrared "); Serial.print((unsigned long)(ir)); Serial.println();
        delay(1000);
    }
}

void loop() {
}
