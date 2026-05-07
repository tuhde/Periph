#include <Wire.h>
#include "I2CTransport.h"
#include "PCF8576.h"

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
PCF8576Full lcd(transport);

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

    lcd.clear();
    check_true("clear: no exception", true);

    lcd.set_digit_7seg(0, PCF8576Minimal::SEG_7SEG[0]);
    check_true("set_digit_7seg: no exception", true);

    uint8_t data[2] = { 0xED, 0x60 };
    lcd.write_raw(0, data, 2);
    check_true("write_raw: no exception", true);

    lcd.enable();
    check_true("enable: no exception", true);

    lcd.disable();
    check_true("disable: no exception", true);

    lcd.set_mode(4, 0);
    check_true("set_mode: no exception", true);

    lcd.set_blink(PCF8576Full::BLINK_OFF);
    check_true("set_blink: no exception", true);

    lcd.set_bank(0, 0);
    check_true("set_bank: no exception", true);

    lcd.device_select(0);
    check_true("device_select: no exception", true);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}