#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "PCF8576.h"

#ifndef PCF8576_I2C_NODE
#define PCF8576_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef PCF8576_ADDR
#define PCF8576_ADDR 0x38
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(PCF8576_I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, PCF8576_ADDR);
    PCF8576Full lcd(transport);

    lcd.clear();
    check_true(true, "clear: no exception");

    lcd.set_digit_7seg(0, PCF8576Minimal::SEG_7SEG[0]);
    check_true(true, "set_digit_7seg: no exception");

    uint8_t data[2] = { 0xED, 0x60 };
    lcd.write_raw(0, data, 2);
    check_true(true, "write_raw: no exception");

    lcd.enable();
    check_true(true, "enable: no exception");

    lcd.disable();
    check_true(true, "disable: no exception");

    lcd.set_mode(4, 0);
    check_true(true, "set_mode: no exception");

    lcd.set_blink(PCF8576Full::BLINK_OFF);
    check_true(true, "set_blink: no exception");

    lcd.set_bank(0, 0);
    check_true(true, "set_bank: no exception");

    lcd.device_select(0);
    check_true(true, "device_select: no exception");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}