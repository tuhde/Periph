#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../../src/connection/I2CConnection.h"
#include "../../../src/chips/pressure/BMP581.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, 0x46);
    BMP581Minimal bmp(connection);                        // Create BMP581 driver, (connection, spi=false)

    for (int i = 0; i < 5; i++) {
        float p = bmp.pressure();                         // Read pressure, () → float Pa
        float t = bmp.temperature();                      // Read temperature, () → float °C
        Serial.print(t, 1);
        Serial.print(" C, ");
        Serial.print(p, 1);
        Serial.println(" Pa");
        delay(1000);
    }

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }