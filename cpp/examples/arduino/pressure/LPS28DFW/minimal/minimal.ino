#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x5C
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/connection/I2CConnection.h"
#include "../../src/chips/pressure/LPS28DFW.h"

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, TEST_ADDR);
    LPS28DFWMinimal lps(connection);                            // Create LPS28DFW driver, (connection)

    for (int i = 0; i < 5; i++) {
        float t = lps.read_temperature();                       // Read temperature, () → float °C
        float p = lps.read_pressure();                          // Read pressure, () → float hPa
        Serial.print(t, 1);
        Serial.print(" C, ");
        Serial.print(p, 1);
        Serial.println(" hPa");
        delay(1000);
    }
    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }