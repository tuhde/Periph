#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/environmental/BME680.h"

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
    BME680Minimal bme(transport);                        // Create BME680 driver, (transport)

    for (int i = 0; i < 5; i++) {
        float t = bme.temperature();                     // Read temperature, () → float °C
        float p = bme.pressure();                       // Read pressure, () → float hPa
        float h = bme.humidity();                       // Read humidity, () → float %RH
        float g = bme.gas_resistance();                 // Read gas resistance, () → float Ω
        Serial.print(t, 1);
        Serial.print(" C, ");
        Serial.print(p, 1);
        Serial.print(" hPa, ");
        Serial.print(h, 1);
        Serial.print(" %RH, ");
        Serial.print(g, 0);
        Serial.println(" Ohm");
        delay(5000);
    }

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
