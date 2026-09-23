#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "I2CConnection.h"
#include "L3G4200D.h"

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, 0x68);
    L3G4200DMinimal gyro(connection);                     // Create L3G4200D driver, (connection, spi=false)

    for (int i = 0; i < 10; i++) {
        float x, y, z;
        gyro.angular_rate(x, y, z);                       // Read X/Y/Z angular rate, () → (float, float, float) rad/s
        Serial.print("X=");
        Serial.print(x, 3);
        Serial.print(" Y=");
        Serial.print(y, 3);
        Serial.print(" Z=");
        Serial.print(z, 3);
        Serial.println(" rad/s");
        delay(100);
    }
    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
