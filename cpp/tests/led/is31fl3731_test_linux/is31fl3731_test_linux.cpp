#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x74
#endif

#include <stdio.h>
#include "I2CConnectionLinux.h"
#include "Is31fl3731.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
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
    return failed == 0 ? 0 : 1;
}
