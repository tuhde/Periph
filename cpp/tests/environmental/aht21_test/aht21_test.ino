#include <Wire.h>
#include "I2CTransport.h"
#include "AHT21.h"

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
#define TEST_ADDR 0x38
#endif

I2CTransport transport(Wire, TEST_ADDR);
AHT21Full    aht(transport);

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

    check_true("is_calibrated", aht.is_calibrated());
    check_true("not busy at idle", !aht.is_busy());

    float t, h;
    aht.read(t, h);
    check_true("temperature range", t >= -40.0f && t <= 120.0f);
    check_true("humidity range", h >= 0.0f && h <= 100.0f);

    float tr = aht.temperature();
    check_true("read_temperature range", tr >= -40.0f && tr <= 120.0f);

    float hr = aht.humidity();
    check_true("read_humidity range", hr >= 0.0f && hr <= 100.0f);

    float tc, hc;
    bool crc_ok = aht.read_with_crc(tc, hc);
    check_true("crc_ok", crc_ok);
    check_true("crc temperature range", tc >= -40.0f && tc <= 120.0f);
    check_true("crc humidity range", hc >= 0.0f && hc <= 100.0f);

    aht.soft_reset();
    delay(50);
    check_true("calibrated after reset", aht.is_calibrated());

    float t2, h2;
    aht.read(t2, h2);
    check_true("read after reset: temperature range", t2 >= -40.0f && t2 <= 120.0f);
    check_true("read after reset: humidity range", h2 >= 0.0f && h2 <= 100.0f);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
