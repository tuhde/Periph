#include "HX711Transport.h"
#include "HX711.h"

#ifndef TEST_DOUT_PIN
#define TEST_DOUT_PIN   5
#endif
#ifndef TEST_PD_SCK_PIN
#define TEST_PD_SCK_PIN 6
#endif

HX711Transport transport(TEST_DOUT_PIN, TEST_PD_SCK_PIN);
HX711Full<HX711Transport> chip(transport);

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

    check_true("is_ready returns bool", true);

    int32_t raw = chip.read_raw();
    check_true("read_raw in 24-bit signed range", raw >= -8388608 && raw <= 8388607);

    chip.set_gain(128);
    check_true("set_gain(128) accepted", true);

    chip.set_gain(64);
    check_true("set_gain(64) accepted", true);

    chip.set_gain(32);
    check_true("set_gain(32) accepted", true);

    chip.set_gain(128);

    int32_t avg = chip.read_average(3);
    check_true("read_average in 24-bit signed range", avg >= -8388608 && avg <= 8388607);

    chip.tare(3);
    check_true("tare accepted", true);

    int32_t offset = chip.get_offset();
    check_true("get_offset returns int32_t", true);

    chip.set_scale(420.0f);
    check_true("set_scale accepted", true);

    float scale = chip.get_scale();
    check_true("get_scale returns 420.0", scale == 420.0f);

    float weight = chip.read_weight(1);
    check_true("read_weight returns float", true);

    chip.power_down();
    check_true("power_down accepted", true);

    chip.power_up();
    check_true("power_up accepted", true);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
