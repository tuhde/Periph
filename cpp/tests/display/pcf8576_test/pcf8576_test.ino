#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x38
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/display/PCF8576.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, TEST_ADDR);
    PCF8576Minimal lcd(transport);
    PCF8576Full lcd_full(transport);

    check_true(lcd._cmd_mode(false, PCF8576Minimal::BIAS_1_3, PCF8576Minimal::MODE_1_4) == 0x40, "mode_set_off");
    check_true(lcd._cmd_mode(true,  PCF8576Minimal::BIAS_1_3, PCF8576Minimal::MODE_1_4) == 0x48, "mode_set_on");
    check_true(lcd._cmd_mode(true,  PCF8576Minimal::BIAS_1_3, PCF8576Minimal::MODE_STATIC) == 0x49, "mode_set_static");
    check_true(lcd._cmd_mode(true,  PCF8576Minimal::BIAS_1_2, PCF8576Minimal::MODE_1_4) == 0x4C, "mode_set_half_bias");

    check_true(PCF8576Minimal::SEVEN_SEG[0] == 0xED, "seven_seg_0");
    check_true(PCF8576Minimal::SEVEN_SEG[9] == 0xEB, "seven_seg_9");

    lcd.clear();
    check_true(true, "clear");

    lcd.set_digit_7seg(0, 0xED);
    lcd.set_digit_7seg(1, 0x60);
    check_true(true, "set_digit_7seg");

    uint8_t bytes[4] = {0xED, 0x60, 0xA7, 0xE3};
    lcd.write_raw(0, bytes, 4);
    check_true(true, "write_raw");

    lcd_full.enable();
    lcd_full.disable();
    lcd_full.enable();
    check_true(true, "enable_disable");

    lcd_full.set_mode(PCF8576Full::BACKPLANES_4, PCF8576Full::BIAS_1_3);
    lcd_full.set_mode(PCF8576Full::BACKPLANES_2, PCF8576Full::BIAS_1_2);
    check_true(true, "set_mode");

    lcd_full.set_blink(PCF8576Full::BLINK_2_HZ);
    lcd_full.set_blink(PCF8576Full::BLINK_OFF);
    check_true(true, "set_blink");

    lcd_full.set_bank(0, 1);
    lcd_full.set_bank(1, 0);
    check_true(true, "set_bank");

    lcd_full.device_select(0);
    lcd_full.device_select(7);
    check_true(true, "device_select");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
