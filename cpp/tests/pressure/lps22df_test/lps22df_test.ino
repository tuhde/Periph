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
#include "../../src/chips/pressure/LPS22DF.h"

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
    LPS22DFMinimal lps(connection);

    float t = lps.temperature();
    check_true(t >= -40.0f && t <= 85.0f, "temperature_range");

    float p = lps.pressure();
    check_true(p >= 26000.0f && p <= 126000.0f, "pressure_range");

    LPS22DFFull lps_full(connection);
    lps_full.configure(3, 0, true, 1, true);
    float p2 = lps_full.pressure();
    check_true(p2 >= 26000.0f && p2 <= 126000.0f, "configure_then_read");

    float alt = lps_full.altitude(101325.0f);
    check_true(alt >= -500.0f && alt <= 10000.0f, "altitude");

    uint8_t who = lps_full.who_am_i();
    check_true(who == 0xB4, "who_am_i");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }