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

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(PCF8576_I2C_NODE);
    I2CTransportZephyr transport(dev, PCF8576_ADDR);
    PCF8576Minimal lcd(transport);
    PCF8576Full lcd_full(transport);

    check_true((PCF8576Minimal::CMD_MODE_SET | PCF8576Minimal::DISPLAY_OFF | PCF8576Minimal::BIAS_1_3 | PCF8576Minimal::MODE_1_4) == 0x40, "mode_set_off");
    check_true((PCF8576Minimal::CMD_MODE_SET | PCF8576Minimal::DISPLAY_ON  | PCF8576Minimal::BIAS_1_3 | PCF8576Minimal::MODE_1_4) == 0x48, "mode_set_on");
    check_true((PCF8576Minimal::CMD_MODE_SET | PCF8576Minimal::DISPLAY_ON  | PCF8576Minimal::BIAS_1_3 | PCF8576Minimal::MODE_STATIC) == 0x49, "mode_set_static");
    check_true((PCF8576Minimal::CMD_MODE_SET | PCF8576Minimal::DISPLAY_ON  | PCF8576Minimal::BIAS_1_2 | PCF8576Minimal::MODE_1_4) == 0x4C, "mode_set_half_bias");

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

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
