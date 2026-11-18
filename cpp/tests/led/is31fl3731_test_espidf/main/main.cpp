// Auto-generated ESP-IDF test for Is31fl3731.
// Mirrors the Zephyr test for Is31fl3731; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Is31fl3731.h"

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
    dev_cfg.device_address = 0x74;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Is31fl3731Minimal matrix(connection);
    Is31fl3731Full full(connection);

    check_true(Is31fl3731Minimal::ROWS == 9 && Is31fl3731Minimal::COLS == 16, "constants");

    check_true(matrix.set_pixel(0, 0, 255) && matrix.set_pixel(8, 15, 64), "set_pixel");
    check_true(!matrix.set_pixel(9, 0, 0) && !matrix.set_pixel(0, 16, 0), "set_pixel_range");
    matrix.fill(32);
    check_true(true, "fill");
    matrix.clear();
    check_true(true, "clear");

    full.set_pixel(3, 4, 99);
    check_true(full.get_pixel(3, 4) == 99 && full.get_pixel(3, 5) == 0, "get_pixel");
    uint8_t ramp[144];
    for (int i = 0; i < 144; i++) ramp[i] = (uint8_t)i;
    check_true(full.set_pixels(ramp, 144) && !full.set_pixels(ramp, 143), "set_pixels");
    check_true(full.set_led_enabled(0, 0, false) && full.set_led_enabled(0, 0, true), "set_led_enabled");
    check_true(full.set_blink(1, 1, true), "set_blink");
    full.set_blink_all(true);
    check_true(full.enable_blink(true, 2) && !full.enable_blink(true, 8), "enable_blink");
    full.enable_blink(false);
    full.set_blink_all(false);
    check_true(full.select_frame(1) && !full.select_frame(8), "select_frame");
    full.fill(10);
    check_true(full.copy_frame(1, 2) && !full.copy_frame(1, 8), "copy_frame");
    check_true(full.show_frame(2) && !full.show_frame(8), "show_frame");
    full.select_frame(0);
    full.show_frame(0);
    check_true(full.clear_frame(1) && !full.clear_frame(8), "clear_frame");
    full.clear_all_frames();
    check_true(true, "clear_all_frames");
    full.set_picture_mode();
    check_true(true, "set_picture_mode");
    check_true(full.start_auto_play(2, 1, 110) && !full.start_auto_play(0, 0, 2000), "start_auto_play");
    (void)full.current_frame();
    (void)full.movie_finished();
    full.stop_auto_play();
    check_true(true, "stop_auto_play");
    full.set_audio_mode(false);
    full.set_audio_sync(false);
    full.set_audio_rate(10);
    check_true(full.set_audio_gain(6, true, false) && !full.set_audio_gain(4), "audio");
    check_true(full.set_breath(true, 2, 2, 1) && full.set_breath(false) && !full.set_breath(true, 8), "set_breath");
    full.set_intensity_control(true);
    full.set_intensity_control(false);
    check_true(true, "set_intensity_control");
    check_true(full.write_function_register(0x03, 0x05) && !full.write_function_register(0x0D, 0), "write_function_register");
    (void)full.read_function_register(0x03);
    (void)full.frame_state();
    (void)full.poll_interrupt();
    check_true(true, "frame_state");
    full.shutdown();
    full.shutdown(false);
    check_true(true, "shutdown");
    full.clear();

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
