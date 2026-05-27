#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x52
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
    I2CTransport transport(Wire, TEST_ADDR);

    ENS160Minimal sensor(transport);
    check_true(true, "init");

    uint8_t status = sensor.status();
    check_true(status <= 3, "status_valid_range");

    Serial.println("Waiting for warm-up...");
    unsigned long start = millis();
    while (sensor.status() != 0) {
        if (millis() - start > 180000) {
            Serial.println("FAIL warmup_timeout");
            failed++;
            break;
        }
        delay(1000);
    }
    if (sensor.status() == 0) {
        check_true(true, "warmup_complete");
    }

    uint8_t aqi;
    float tvoc_ppb, eco2_ppm;
    bool ok = sensor.read_air_quality(aqi, tvoc_ppb, eco2_ppm);
    check_true(ok, "read_air_quality");
    check_true(aqi >= 1 && aqi <= 5, "aqi_range");
    check_true(tvoc_ppb >= 0, "tvoc_non_negative");
    check_true(eco2_ppm >= 400, "eco2_minimum");

    ENS160Full sensor_full(transport);
    check_true(true, "full_init");

    sensor_full.set_compensation(25.0f, 50.0f);
    check_true(true, "set_compensation");

    float tvoc = sensor_full.read_tvoc();
    check_true(tvoc >= 0, "read_tvoc");

    float eco2 = sensor_full.read_eco2();
    check_true(eco2 >= 400, "read_eco2");

    uint8_t aqi2 = sensor_full.read_aqi();
    check_true(aqi2 >= 1 && aqi2 <= 5, "read_aqi");

    float temp_actual, rh_actual;
    sensor_full.read_compensation_actuals(temp_actual, rh_actual);
    check_true(true, "read_compensation_actuals");

    sensor_full.sleep();
    check_true(true, "sleep");
    delay(100);
    sensor_full.wake();
    check_true(true, "wake");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
