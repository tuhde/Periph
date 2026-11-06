#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "PCF8579.h"

int passed = 0;
int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_near(float v, float lo, float hi, const char *label) {
    if (v >= lo && v <= hi) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: %.4f not in [%.4f, %.4f]\n", label, (double)v, (double)lo, (double)hi); failed++; }
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x3C);
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

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    while (true) sleep_ms(1000);
}
