#include "DHT11PinArduino.h"
#include "DHT11.h"

#ifndef TEST_DATA_PIN
#define TEST_DATA_PIN 4
#endif

DHT11PinArduino pin(TEST_DATA_PIN);
DHT11Full<DHT11PinArduino> dht(pin);

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

    float t, h;
    bool ok = dht.read(t, h);
    check_true("read returned bool", true);
    check_true("read_temperature in [-20, 60]", t >= -20.0f && t <= 60.0f);
    check_true("read_humidity in [0, 100]",    h >=   0.0f && h <= 100.0f);

    float t2 = 0.0f, h2 = 0.0f;
    bool ok2 = dht.read_retry(t2, h2, 3);
    check_true("read_retry returned bool", true);
    check_true("read_retry temperature in [-20, 60]", t2 >= -20.0f && t2 <= 60.0f);
    check_true("read_retry humidity in [0, 100]",    h2 >=   0.0f && h2 <= 100.0f);

    uint8_t raw[5];
    bool raw_ok = dht.read_raw(raw);
    check_true("read_raw returned bool", true);
    check_true("read_raw length is 5",     raw_ok);
    if (raw_ok) {
        uint8_t checksum = (uint8_t)(raw[0] + raw[1] + raw[2] + raw[3]);
        check_true("read_raw checksum OK", checksum == raw[4]);
    }

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
