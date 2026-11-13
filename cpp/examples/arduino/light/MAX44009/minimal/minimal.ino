#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, MAX44009Minimal::I2C_ADDRESS);
MAX44009Minimal sensor(connection);                     // Create MAX44009 driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    for (int i = 0; i < 10; i++) {
        float lx = sensor.lux();                        // Read illuminance, () → float lux
        Serial.print("lux "); Serial.print((double)lx, 2); Serial.println(" lx");
        delay(1000);
    }
}

void loop() {
}
