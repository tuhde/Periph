#include <Wire.h>
#include "I2CTransport.h"
#include "INA226.h"

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
INA226Full   ina(transport);

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint16_t got, uint16_t expected) {
    if (got == expected) {
        Serial.print("PASS "); Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL "); Serial.print(label);
        Serial.print(": got 0x"); Serial.print(got, HEX);
        Serial.print(", expected 0x"); Serial.println(expected, HEX);
        failed++;
    }
}

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

    check_eq("manufacturer_id", ina.manufacturer_id(), 0x5449);
    check_eq("die_id",          ina.die_id(),          0x2260);

    check_true("voltage non-negative",     ina.voltage()       >= 0.0f);
    check_true("shunt_voltage finite",     ina.shunt_voltage() > -1.0f);
    check_true("current finite",           ina.current()       > -10.0f);
    check_true("power non-negative",       ina.power()         >= 0.0f);

    check_true("conversion_ready", ina.conversion_ready());
    check_true("no overflow",      !ina.overflow());

    ina.configure(3, 4, 4, 7);
    check_eq("configure: mfr_id still valid", ina.manufacturer_id(), 0x5449);

    ina.set_alert(INA226Full::POL, 1.0f, false, true);
    check_true("set_alert POL: LEN bit set", (ina.alert_flags() & 0x0001) != 0);

    ina.shutdown();
    delay(1);
    ina.wake();
    check_true("wake: voltage non-negative", ina.voltage() >= 0.0f);

    ina.reset();
    check_eq("reset: mfr_id still valid", ina.manufacturer_id(), 0x5449);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
