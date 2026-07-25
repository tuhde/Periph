#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/pressure/BMP280.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, 0x76);
    BMP280Minimal bmp(transport);                        // Create BMP280 driver, (transport, spi=false)

    for (int i = 0; i < 5; i++) {
        float t = bmp.temperature();                     // Read temperature, () → float °C
        float p = bmp.pressure();                       // Read pressure, () → float hPa
        Serial.print(t, 1);
        Serial.print(" C, ");
        Serial.print(p, 1);
        Serial.println(" hPa");
        delay(1000);
    }

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
