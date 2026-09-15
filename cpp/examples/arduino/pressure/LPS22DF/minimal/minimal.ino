#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/connection/I2CConnection.h"
#include "../../src/chips/pressure/LPS22DF.h"

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, 0x5C);
    LPS22DFMinimal lps(connection);                        // Create LPS22DF driver, (connection, spi=false)

    for (int i = 0; i < 5; i++) {
        float p = lps.pressure();                         // Read pressure, () → float Pa
        float t = lps.temperature();                      // Read temperature, () → float °C
        Serial.print(t, 1);
        Serial.print(" C, ");
        Serial.print(p, 0);
        Serial.println(" Pa");
        delay(1000);
    }
    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }