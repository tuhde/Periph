#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x77
#endif

#include <Arduino.h>
#include <Wire.h>
#include "I2CConnection.h"
#include "BMP085.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, TEST_ADDR);
    BMP085Minimal bmp(connection);

    float t = bmp.temperature();
    check_true(t >= -40.0f && t <= 85.0f, "temperature_range");

    float p = bmp.pressure();
    check_true(p >= 30000.0f && p <= 110000.0f, "pressure_range");

    BMP085Full bmp_full(connection, BMP085Full::OSS_ULP);
    check_true(bmp_full.oversampling() == BMP085Full::OSS_ULP, "default_oss");
    bmp_full.set_oversampling(BMP085Full::OSS_HIGH_RES);
    check_true(bmp_full.oversampling() == BMP085Full::OSS_HIGH_RES, "set_oss");

    float alt = bmp_full.altitude();
    check_true(alt >= -500.0f && alt <= 9000.0f, "altitude_range");

    float slp = bmp_full.sea_level_pressure(0.0f);
    check_true(slp >= 90000.0f && slp <= 110000.0f, "sea_level_pressure");

    check_true(bmp_full.chip_id() == 0x55, "chip_id");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }