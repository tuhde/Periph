#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, PCA9541Minimal::I2C_ADDRESS);
PCA9541Minimal selector(connection);                    // Create PCA9541 driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    bool before = selector.hasControl();                // Report control, () → bool
    Serial.print("has control "); Serial.println((int)before);
    bool ok = selector.acquire();                       // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    Serial.print("acquire "); Serial.println((int)ok);
    delay(1000);
    selector.release();                                 // Give the bus to the other master, () → void
    Serial.print("has control "); Serial.println((int)selector.hasControl());
}

void loop() {
}
