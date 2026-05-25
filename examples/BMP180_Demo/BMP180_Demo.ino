#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/pressure/BMP180.h"

static int passed = 0, failed = 0;

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, 0x77);
    BMP180Full bmp(transport, BMP180Full.OSS_ULP);  // Create BMP180 driver, (transport, oss=0 ULP)

    float t0 = bmp.temperature();                     // Read temperature, () → float C
    float p0 = bmp.pressure();                       // Read pressure, () → float hPa
    float alt_ref = bmp.altitude();                  // Compute altitude, (sea_level_hpa=1013.25) → float m
    Serial.print("Reference: ");
    Serial.print(t0, 1);
    Serial.print(" C, ");
    Serial.print(p0, 1);
    Serial.print(" hPa, alt=");
    Serial.print(alt_ref, 1);
    Serial.println(" m");

    float prev_alt = 0.0f;
    for (int n = 0; n < 60; n++) {
        float t = bmp.temperature();                 // Read temperature, () → float C
        float p = bmp.pressure();                  // Read pressure, () → float hPa
        float a = bmp.altitude();                 // Compute altitude, (sea_level_hpa=1013.25) → float m
        float da = (a - prev_alt) * 100.0f;

        if (n > 0) {
            Serial.print(n);
            Serial.print("s: ");
            Serial.print(t, 1);
            Serial.print(" C, ");
            Serial.print(p, 1);
            Serial.print(" hPa, alt=");
            Serial.print(a, 1);
            Serial.print(" m (delta=");
            Serial.print(da, 0);
            Serial.println(" cm)");
        } else {
            Serial.print(n);
            Serial.print("s: ");
            Serial.print(t, 1);
            Serial.print(" C, ");
            Serial.print(p, 1);
            Serial.print(" hPa, alt=");
            Serial.print(a, 1);
            Serial.println(" m");
        }
        prev_alt = a;
        delay(1000);
    }

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
