#include <Wire.h>
#include "I2CTransport.h"
#include "INA219.h"

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_I2C_FREQ
#define TEST_I2C_FREQ 400000
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x40
#endif

I2CTransport transport(Wire, TEST_ADDR);
INA219Full   ina(transport);

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) {
        Serial.print("PASS "); Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL "); Serial.println(label);
        failed++;
    }
}

void setup() {
    Serial.begin(115200);
    delay(2000);

    Wire.begin(TEST_SDA, TEST_SCL, TEST_I2C_FREQ);

    check_true("voltage non-negative",     ina.voltage()       >= 0.0f);
    check_true("shunt_voltage finite",     ina.shunt_voltage() > -1.0f);
    check_true("current finite",           ina.current()       > -10.0f);
    check_true("power non-negative",       ina.power()         >= 0.0f);

    check_true("conversion_ready", ina.conversion_ready());
    check_true("no overflow",      !ina.overflow());

    ina.configure(1, 3, 3, 3, 7);
    check_true("voltage after configure", ina.voltage() >= 0.0f);

    ina.shutdown();
    delay(1);
    ina.wake();
    check_true("wake: voltage non-negative", ina.voltage() >= 0.0f);

    ina.reset();
    check_true("reset: voltage non-negative", ina.voltage() >= 0.0f);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
