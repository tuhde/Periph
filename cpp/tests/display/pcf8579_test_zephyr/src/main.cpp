#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "PCF8579.h"

#ifndef PCF8579_I2C_NODE
#define PCF8579_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef PCF8579_ADDR
#define PCF8579_ADDR 0x3C
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(PCF8579_I2C_NODE);
    I2CConnectionZephyr connection(dev, PCF8579_ADDR);
    PCF8579Minimal lcd(connection, 0, 16);
    lcd.clear();
    lcd.set_pixel(0, 0);
    lcd.set_pixel(39, 15);
    lcd.set_pixel(45, 30);                                    // out of range, ignored
    static const uint8_t cols[3] = {0xFF, 0x81, 0xFF};
    check_true(lcd.write_columns(2, 1, cols, sizeof(cols)), "minimal_draw");

    // Full: exercise every method; read_ram verifies the bytes really landed.
    PCF8579Full full(connection, 0, 16);
    static const uint8_t ram[3] = {0xAA, 0x55, 0x0F};
    check_true(full.write_ram(16, 0, ram, sizeof(ram)), "write_ram");
    uint8_t got[3] = {0, 0, 0};
    check_true(full.read_ram(16, 0, got, sizeof(got)) && got[0] == 0xAA && got[1] == 0x55 && got[2] == 0x0F,
               "ram_roundtrip");

    full.fill(true);
    full.fill(false);
    full.draw_line(0, 0, 39, 15);
    full.draw_rect(0, 0, 40, 16);
    full.draw_rect(4, 4, 8, 8, true, true);
    static const uint8_t bmp[4] = {0x18, 0x24, 0x42, 0x81};
    bool bmp_ok = full.show_bitmap(0, 0, bmp, sizeof(bmp));
    check_true(bmp_ok && full.get_pixel(0, 0), "graphics");

    static const uint8_t zeros[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    bool cfg_ok = full.set_display(PCF8579Full::DISPLAY_INVERSE)
        && full.set_display(PCF8579Full::DISPLAY_ALL_ON)
        && full.set_display(PCF8579Full::DISPLAY_NORMAL)
        && full.set_start_bank(1);
    full.scroll(1);
    full.scroll(-2);
    cfg_ok = cfg_ok
        && full.write_ram(16, 0, zeros, sizeof(zeros), PCF8579Full::ACCESS_HALF_GRAPHIC)
        && full.write_ram(16, 0, zeros, sizeof(zeros), PCF8579Full::ACCESS_FULL_GRAPHIC)
        && full.set_mode(24, false) && full.set_mode(16, false);
    full.reset();
    check_true(cfg_ok, "configuration");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return 0;
}
