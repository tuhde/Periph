#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, TSL2561Minimal::I2C_ADDRESS);
TSL2561Minimal sensor(connection);                      // Create TSL2561 driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    sensor.init();                                      // Power up, 1× gain, 402 ms, wait one integration, () → bool
    for (int i = 0; i < 10; i++) {
        float lx = sensor.readLux();                    // Read illuminance, () → float lux
        Serial.print("lux "); Serial.print((double)lx, 1); Serial.println(" lx");
        delay(1000);
    }
}

void loop() {
}
