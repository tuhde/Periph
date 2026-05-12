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
    I2CTransport transport(Wire, TEST_ADDR);
    BMP180Minimal bmp(transport);

    bmp._oss = 0;
    bmp._b5 = 0;
    bmp._ac1 = 408;
    bmp._ac2 = -72;
    bmp._ac3 = -14383;
    bmp._ac4 = 32741;
    bmp._ac5 = 32757;
    bmp._ac6 = 23153;
    bmp._b1 = 6190;
    bmp._b2 = 4;
    bmp._mc = -8711;
    bmp._md = 2868;

    int32_t b5 = bmp._compensate_temp(27898);
    check_true(abs(b5) > 0, "temp_compensation_b5");

    BMP180Full bmp_full(transport, 0);
    check_true(bmp_full.oversampling() == 0, "default_oss");
    bmp_full.set_oversampling(2);
    check_true(bmp_full.oversampling() == 2, "set_oss");

    float alt = bmp_full.altitude();
    check_true(alt >= 0.0f, "altitude");
    float slp = bmp_full.sea_level_pressure(0.0f);
    check_true(slp >= 900.0f && slp <= 1100.0f, "sea_level_pressure");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
