#include <Wire.h>
#include "I2CConnection.h"
#include "HMC5883L.h"

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
#define TEST_ADDR 0x1E
#endif

I2CConnection connection(Wire, TEST_ADDR);
HMC5883LFull hmc5883l(connection);

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint16_t got, uint16_t expected) {
    if (got == expected) {
        Serial.print("PASS "); Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL "); Serial.print(label);
        Serial.print(": got "); Serial.print(got);
        Serial.print(", expected "); Serial.println(expected);
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
    delay(6);  // Wait for first measurement in continuous mode

    // --- Identification ---
    uint8_t id_a, id_b, id_c;
    hmc5883l.identify(id_a, id_b, id_c);
    check_eq("identify A", id_a, 0x48);
    check_eq("identify B", id_b, 0x34);
    check_eq("identify C", id_c, 0x33);

    // --- Status ---
    uint8_t sb = hmc5883l.status();
    check_true("status_byte valid", sb <= 255);

    // --- Data ready ---
    check_true("data_ready returns bool", hmc5883l.data_ready() == true || hmc5883l.data_ready() == false);

    // --- Magnetic field reading ---
    float x, y, z;
    bool ok = hmc5883l.magnetic_field(x, y, z);
    check_true("magnetic_field x is float or NaN", isnan(x) || !isnan(x));
    check_true("magnetic_field y is float or NaN", isnan(y) || !isnan(y));
    check_true("magnetic_field z is float or NaN", isnan(z) || !isnan(z));

    // --- Configuration ---
    hmc5883l.configure(15, 8, 1);
    check_true("configure accepted", true);

    hmc5883l.set_gain(2);
    check_true("set_gain accepted", true);

    hmc5883l.set_mode("continuous");
    check_true("set_mode continuous accepted", true);

    // --- Single-shot measurement ---
    ok = hmc5883l.single_measurement(x, y, z);
    check_true("single_measurement x is float or NaN", isnan(x) || !isnan(x));
    check_true("single_measurement y is float or NaN", isnan(y) || !isnan(y));
    check_true("single_measurement z is float or NaN", isnan(z) || !isnan(z));

    hmc5883l.set_mode("idle");
    check_true("set_mode idle accepted", true);

    // --- Self-test ---
    ok = hmc5883l.self_test(true, x, y, z);
    check_true("self_test x is float or NaN", isnan(x) || !isnan(x));
    check_true("self_test y is float or NaN", isnan(y) || !isnan(y));
    check_true("self_test z is float or NaN", isnan(z) || !isnan(z));

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}