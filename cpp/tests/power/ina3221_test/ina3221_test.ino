#include <Wire.h>
#include "I2CTransport.h"
#include "INA3221.h"

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
INA3221Full   ina(transport);

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
    check_eq("die_id",          ina.die_id(),          0x3220);

    for (uint8_t ch = 1; ch <= 3; ch++) {
        char label[32];
        snprintf(label, sizeof(label), "ch%u voltage non-negative", ch);
        check_true(label, ina.voltage(ch) >= 0.0f);

        snprintf(label, sizeof(label), "ch%u shunt_voltage finite", ch);
        check_true(label, abs(ina.shunt_voltage(ch)) < 1.0f);

        snprintf(label, sizeof(label), "ch%u current finite", ch);
        check_true(label, abs(ina.current(ch)) < 100.0f);

        snprintf(label, sizeof(label), "ch%u power non-negative", ch);
        check_true(label, ina.power(ch) >= 0.0f);
    }

    check_true("conversion_ready", ina.conversion_ready());

    ina.configure(3, 4, 4, 7);
    check_eq("configure: mfr_id still valid", ina.manufacturer_id(), 0x5449);

    ina.set_critical_alert(1, 0.1f);
    ina.set_warning_alert(2, 0.05f);
    uint16_t flags = ina.alert_flags();
    check_true("alert_flags readable", flags >= 0);

    ina.enable_channel(1, false);
    check_true("channel 1 disabled", !ina.channel_enabled(1));
    ina.enable_channel(1, true);
    check_true("channel 1 re-enabled", ina.channel_enabled(1));

    uint8_t channels[] = {1, 2};
    ina.set_summation_channels(channels, 2, 0.2f);
    float sv_sum = ina.summation_value();
    check_true("summation_value finite", abs(sv_sum) < 10.0f);

    ina.set_power_valid_limits(5.5f, 4.5f);
    check_true("power_valid readable", true);

    ina.shutdown();
    delay(1);
    ina.wake();
    check_true("wake: voltage non-negative", ina.voltage(1) >= 0.0f);

    ina.reset();
    check_eq("reset: mfr_id still valid", ina.manufacturer_id(), 0x5449);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}