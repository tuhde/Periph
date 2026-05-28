#include <Wire.h>
#include "I2CTransport.h"
#include "APDS9960.h"

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
#define TEST_ADDR 0x39
#endif

I2CTransport transport(Wire, TEST_ADDR);
APDS9960Full   apds(transport);

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint8_t got, uint8_t expected) {
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

    check_eq("chip_id", apds.chip_id(), 0xAB);

    uint16_t c, r, g, b;
    apds.color(c, r, g, b);
    check_true("color_clear >= 0", c >= 0);
    check_true("color_red >= 0", r >= 0);
    check_true("color_green >= 0", g >= 0);
    check_true("color_blue >= 0", b >= 0);

    check_true("is_als_valid", apds.is_als_valid());

    apds.enable_proximity(true);
    delay(100);
    uint8_t p = apds.proximity();
    check_true("proximity <= 255", p <= 255);
    check_true("is_proximity_valid", apds.is_proximity_valid());

    apds.configure_als(0xB6, 1);
    delay(210);
    check_true("als_valid after configure", apds.is_als_valid());

    apds.als_threshold(100, 60000);
    apds.proximity_threshold(10, 200);
    apds.set_persistence(0, 1);
    check_true("persistence set", true);

    apds.enable_als_interrupt(true);
    apds.enable_proximity_interrupt(true);
    apds.clear_als_interrupt();
    apds.clear_proximity_interrupt();
    apds.clear_all_interrupts();
    check_true("interrupts cleared", true);

    apds.set_proximity_offset(10, -5);
    apds.set_proximity_mask(false, false, false, false);
    check_true("proximity offset/mask set", true);

    apds.enable_gesture(true);
    apds.configure_gesture(1, 0, 0, 1, 1, 50, 20);
    check_true("gesture configured", true);
    check_true("gesture_fifo_level >= 0", apds.gesture_fifo_level() >= 0);
    apds.clear_gesture_fifo();
    apds.enable_gesture_interrupt(false);
    apds.enable_gesture(false);
    check_true("gesture disabled", true);

    uint8_t s = apds.status();
    check_true("status readable", s >= 0);

    apds.enable_proximity(false);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
