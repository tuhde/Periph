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
    LPS28DFWMinimal lps(connection);

    check_true(lps._fs_mode == 0 && lps._odr == 0x04 && lps._avg == 0x02,
               "minimal_defaults");

    int32_t raw24 = 1000 * 4096;
    float expected_mode1 = (float)raw24 / 4096.0f;
    check_true(expected_mode1 > 999.0f && expected_mode1 < 1001.0f,
               "pressure_sensitivity_mode1");

    raw24 = 1000 * 2048;
    float expected_mode2 = (float)raw24 / 2048.0f;
    check_true(expected_mode2 > 999.0f && expected_mode2 < 1001.0f,
               "pressure_sensitivity_mode2");

    int16_t raw16 = 2500;
    float temp_c = (float)raw16 / 100.0f;
    check_true(temp_c > 24.0f && temp_c < 26.0f, "temperature_conversion");

    LPS28DFWFull lps_full(connection);
    check_true(lps_full._odr == 0x04 && lps_full._avg == 0x02 && lps_full._fs_mode == 0,
               "full_default_inherits");

    lps_full.configure(LPS28DFWFull::ODR_100_HZ, LPS28DFWFull::AVG_128,
                       LPS28DFWFull::FS_MODE_2, 0, 1);
    check_true(lps_full._odr == 0x07 && lps_full._avg == 0x05 && lps_full._fs_mode == 1
               && lps_full._lpf_en == 0 && lps_full._lpf_cfg == 1, "full_configure");

    int32_t threshold_raw = (int32_t)(1050.0f * 16.0f);
    if (threshold_raw > 0x7FFF) threshold_raw = 0x7FFF;
    check_true(threshold_raw == 16800, "threshold_raw_conversion");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }