// Auto-generated ESP-IDF test for PCF8578.
// Mirrors the Zephyr test for PCF8578; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCF8578.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_near(float val, float lo, float hi, const char *label) {
    if (val >= lo && val <= hi) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: %.4f not in [%.4f, %.4f]\n",
                  label, (double)val, (double)lo, (double)hi); failed++; }
}

static void check_eq_u8(uint8_t val, uint8_t expected, const char *label) {
    if (val == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: 0x%02X != 0x%02X\n", label, val, expected); failed++; }
}


extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x3C;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    PCF8578Minimal lcd(connection, 16);
    lcd.clear();
    lcd.set_pixel(0, 0);
    lcd.set_pixel(23, 15);
    lcd.set_pixel(30, 30);  // out of range, ignored
    static const uint8_t cols[3] = {0xFF, 0x81, 0xFF};
    check_true(lcd.write_columns(2, 1, cols, sizeof(cols)), "minimal_draw");

    // Full: exercise every method; read_ram verifies the bytes really landed.
    PCF8578Full full(connection, 16);
    static const uint8_t ram[3] = {0xAA, 0x55, 0x0F};
    check_true(full.write_ram(16, 0, ram, sizeof(ram)), "write_ram");
    uint8_t got[3] = {0, 0, 0};
    check_true(full.read_ram(16, 0, got, sizeof(got)) && got[0] == 0xAA && got[1] == 0x55 && got[2] == 0x0F,
               "ram_roundtrip");

    full.fill(true);
    full.fill(false);
    full.draw_line(0, 0, 23, 15);
    full.draw_rect(0, 0, 24, 16);
    full.draw_rect(4, 4, 8, 8, true, true);
    static const uint8_t bmp[4] = {0x18, 0x24, 0x42, 0x81};
    bool bmp_ok = full.show_bitmap(0, 0, bmp, sizeof(bmp), 1);
    check_true(bmp_ok && full.get_pixel(0, 0), "graphics");

    static const uint8_t zeros[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    bool cfg_ok = full.set_display(PCF8578Full::DISPLAY_INVERSE)
        && full.set_display(PCF8578Full::DISPLAY_ALL_ON)
        && full.set_display(PCF8578Full::DISPLAY_NORMAL)
        && full.set_start_bank(1);
    full.scroll(1);
    full.scroll(-2);
    cfg_ok = cfg_ok && full.device_select(1) && full.device_select(0)
        && full.write_ram(16, 0, zeros, sizeof(zeros), PCF8578Full::ACCESS_HALF_GRAPHIC)
        && full.write_ram(16, 0, zeros, sizeof(zeros), PCF8578Full::ACCESS_FULL_GRAPHIC)
        && full.set_mode(24, true) && full.set_mode(16, true);
    full.reset();
    check_true(cfg_ok, "configuration");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    while (1) vTaskDelay(pdMS_TO_TICKS(1000));
}
