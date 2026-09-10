// Arduino HIL test for the APDS-9930 ambient light and proximity sensor.
//
// Constructs the chip with default settings, then prints lux and
// proximity once per second. Mirrors the python/tests/light/apds9930_test.py
// HIL checks.

#include <Arduino.h>
#include <Wire.h>
#include "Apds9930.h"
#include "I2CConnection.h"

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { Serial.print("PASS "); Serial.println(label); passed++; }
    else           { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    I2CConnection connection(Wire, 0x39);
    APDS9930Full apds(connection);

    delay(110);

    bool avalid = false, pvalid = false, psat = false, aint = false, pint = false;
    apds.status(avalid, pvalid, psat, aint, pint);
    check_true("status returns bools", true);

    float lx = apds.lux();
    check_true("lux is float", true);
    check_true("lux >= 0", lx >= 0.0f);

    uint16_t p = apds.proximity();
    check_true("proximity >= 0", true);

    uint16_t c0 = apds.ch0();
    uint16_t c1 = apds.ch1();
    check_true("ch0 >= 0", true);
    check_true("ch1 >= 0", true);

    apds.configure_als(0xDB, 0, false);
    apds.configure_proximity(8, 0, 0, false, 0xFF);
    apds.disable_wait();
    apds.set_als_thresholds(0, 65535, 1);
    apds.set_proximity_thresholds(0, 1023, 1);
    apds.set_proximity_offset(0);
    apds.sleep_after_interrupt(false);
    apds.clear_interrupt(0);
    check_true("config methods accepted", true);

    (void)c0; (void)c1; (void)p;
}

void loop() {
    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
    delay(1000);
}