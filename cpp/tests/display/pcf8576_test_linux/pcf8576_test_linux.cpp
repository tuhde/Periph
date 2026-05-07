#include <cstdio>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "PCF8576.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x38
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);
    PCF8576Full lcd(transport);

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

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}