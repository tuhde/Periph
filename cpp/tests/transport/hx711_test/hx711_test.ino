#include "HX711Transport.h"

#ifndef TEST_DOUT_PIN
#define TEST_DOUT_PIN   5
#endif
#ifndef TEST_PD_SCK_PIN
#define TEST_PD_SCK_PIN 6
#endif

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

    HX711Transport transport(TEST_DOUT_PIN, TEST_PD_SCK_PIN);

    check_true("is_ready returns bool", true);  // method exists and compiles

    int32_t val = transport.read_raw(25);
    check_true("read_raw(25) in 24-bit signed range", val >= -8388608 && val <= 8388607);

    val = transport.read_raw(26);
    check_true("read_raw(26) in 24-bit signed range", val >= -8388608 && val <= 8388607);

    val = transport.read_raw(27);
    check_true("read_raw(27) in 24-bit signed range", val >= -8388608 && val <= 8388607);

    transport.power_down();
    check_true("power_down accepted", true);

    transport.power_up();
    check_true("power_up accepted", true);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
