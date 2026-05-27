#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/gas/ENS160.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, 0x52);
    ENS160Minimal sensor(transport);                     // Create ENS160 driver, (transport)

    Serial.println("Waiting for sensor warm-up...");
    while (sensor.status() != 0) {                       // Poll validity, () → uint8_t 0–3
        Serial.print("Status: ");
        Serial.println(sensor.status());
        delay(1000);
    }

    for (int i = 0; i < 10; i++) {
        uint8_t aqi;
        float tvoc_ppb, eco2_ppm;
        bool ok = sensor.read_air_quality(aqi, tvoc_ppb, eco2_ppm);  // Read air quality, (aqi&, tvoc_ppb&, eco2_ppm&) → bool
        if (ok) {
            Serial.print("AQI=");
            Serial.print(aqi);
            Serial.print(" TVOC=");
            Serial.print(tvoc_ppb, 0);
            Serial.print(" ppb eCO2=");
            Serial.print(eco2_ppm, 0);
            Serial.println(" ppm");
        }
        delay(1000);
    }

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
