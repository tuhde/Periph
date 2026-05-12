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

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, 0x77);
    BMP180Full bmp(transport);                         // Create BMP180 driver, (transport, oss=0)
    uint8_t cid = bmp.chip_id();                     // Read chip ID, () → int
                                                      // returns 0x55 for BMP180
    check_true(cid == 0x55, "chip_id");

    uint8_t oss = bmp.oversampling();                // Read OSS, () → int 0–3
    check_true(oss == 0, "default_oss");

    bmp.set_oversampling(BMP180Full.OSS_STANDARD);    // Set OSS, (oss 0–3) → None
    check_true(bmp.oversampling() == 1, "set_oss");

    float t = bmp.temperature();                      // Read temperature, () → float C
    float p = bmp.pressure();                        // Read pressure, () → float hPa
    float alt = bmp.altitude();                     // Compute altitude, (sea_level_hpa=1013.25) → float m
    float slp = bmp.sea_level_pressure(alt);         // Compute sea-level pressure, (altitude_m) → float hPa
    bmp.reset();                                     // Soft reset chip, () → None

    Serial.print("T=");
    Serial.print(t, 1);
    Serial.print(" C, P=");
    Serial.print(p, 1);
    Serial.print(" hPa, alt=");
    Serial.print(alt, 1);
    Serial.print(" m, slp=");
    Serial.print(slp, 1);
    Serial.println(" hPa");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
