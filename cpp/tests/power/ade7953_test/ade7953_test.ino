#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x38
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>

static const float VOLTAGE_GAIN = 251.0f;
static const float CURRENT_GAIN = 30.0f;

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool cond) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else      { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection conn(Wire, TEST_ADDR);
    ADE7953Full ade(conn, VOLTAGE_GAIN, CURRENT_GAIN);

    check_true("voltage non-negative", ade.voltage() >= 0.0f);
    check_true("current non-negative", ade.current() >= 0.0f);
    check_true("activePower finite",   ade.activePower() > -1.0e6f);
    check_true("activeEnergy finite",  ade.activeEnergy() > -1000.0f);
    check_true("linePeriod positive",  ade.linePeriod() > 0.0f);

    ade.reset();
    check_true("voltage after reset", ade.voltage() >= 0.0f);

    Serial.print("===DONE: "); Serial.print(passed);
    Serial.print(" passed, "); Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
