#include <stdio.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "Is31fl3731.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

typedef std::vector<uint8_t> Bytes;

static bool eq(const Bytes& a, std::initializer_list<uint8_t> b) { return a == Bytes(b); }

// A register write of `n` bytes of value `v` (reg + payload).
static Bytes rep(uint8_t reg, size_t n, uint8_t v) {
    Bytes b(n + 1, v);
    b[0] = reg;
    return b;
}

// Count the PWM block writes of one full frame (9 chunks of 16 bytes).
static size_t count_pwm_chunks(const std::vector<Bytes>& w) {
    size_t n = 0;
    for (const auto& x : w) if (x.size() == 17 && x[0] >= 0x24) n++;
    return n;
}

int main() {
    I2CConnectionMock c;
    Is31fl3731Full d(c);
    const auto& w = c.writes();

    // init: function page, shutdown, config, display, frame 1 (18+18 bytes in 16-byte chunks, 144 in 9), run
    size_t i = 0;
    check_true(eq(w[i++], {0xFD, 0x0B}) && eq(w[i++], {0x0A, 0x00}) && eq(w[i++], {0x00, 0x00, 0x00})
               && eq(w[i++], {0x05, 0x00}) && eq(w[i++], {0xFD, 0x00}), "init_prefix");
    check_true(w[i] == rep(0x00, 16, 0xFF) && eq(w[i + 1], {0x10, 0xFF, 0xFF}), "init_ctrl_chunks");
    i += 2;
    check_true(w[i] == rep(0x12, 16, 0) && eq(w[i + 1], {0x22, 0, 0}), "init_blink_chunks");
    i += 2;
    bool pwm_ok = true;
    for (int k = 0; k < 9; k++) pwm_ok = pwm_ok && w[i + k] == rep(0x24 + 16 * k, 16, 0);
    check_true(pwm_ok, "init_pwm_chunks");
    i += 9;
    check_true(eq(w[i], {0xFD, 0x0B}) && eq(w[i + 1], {0x0A, 0x01}) && w.size() == i + 2, "init_run");

    c.clearWrites();
    check_true(d.set_pixel(2, 5, 200), "set_pixel");
    check_true(w.size() == 2 && eq(w[0], {0xFD, 0x00}) && eq(w[1], {0x24 + 37, 200}), "set_pixel_page_and_pwm");
    c.clearWrites();
    d.set_pixel(8, 15, 1);
    check_true(w.size() == 1 && eq(w[0], {0x24 + 143, 1}), "set_pixel_cached_page");
    check_true(d.get_pixel(2, 5) == 200 && d.get_pixel(0, 0) == 0, "get_pixel");
    check_true(!d.set_pixel(9, 0, 0) && !d.set_pixel(0, 16, 0), "pixel_range");

    c.clearWrites();
    d.fill(0x40);
    check_true(w.size() == 9 && w[0] == rep(0x24, 16, 0x40) && w[8] == rep(0x24 + 128, 16, 0x40), "fill");
    d.clear();
    check_true(w.back() == rep(0x24 + 128, 16, 0) && d.get_pixel(2, 5) == 0, "clear");

    uint8_t ramp[144];
    for (int k = 0; k < 144; k++) ramp[k] = (uint8_t)k;
    c.clearWrites();
    check_true(d.set_pixels(ramp, 144) && !d.set_pixels(ramp, 143), "set_pixels_len");
    check_true(w.size() == 9 && w[1][1] == 16 && w[8][16] == 143 && d.get_pixel(1, 1) == 17, "set_pixels");

    c.clearWrites();
    d.set_led_enabled(1, 9, false);
    check_true(eq(w.back(), {0x03, 0xFD}), "led_off");
    d.set_led_enabled(1, 9, true);
    check_true(eq(w.back(), {0x03, 0xFF}), "led_on");
    d.set_blink(1, 9, true);
    check_true(eq(w.back(), {0x15, 0x02}), "blink_bit");
    d.set_blink_all(true);
    check_true(w.back() == rep(0x22, 2, 0xFF), "blink_all");
    d.set_blink_all(false);
    check_true(w.back() == rep(0x22, 2, 0x00), "blink_all_off");

    c.clearWrites();
    check_true(d.select_frame(3) && !d.select_frame(8), "frame_range");
    check_true(w.size() == 1 && eq(w[0], {0xFD, 0x03}), "select_frame");
    d.set_pixel(0, 0, 9);
    check_true(eq(w.back(), {0x24, 9}), "draw_on_frame3");
    c.clearWrites();
    check_true(d.copy_frame(3, 5) && !d.copy_frame(0, 8), "copy_range");
    check_true(eq(w[0], {0xFD, 0x05}) && eq(w[1], {0x24, 9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}), "copy_frame");
    c.clearWrites();
    d.clear_frame(5);
    check_true(w[0] == rep(0x00, 16, 0xFF) && count_pwm_chunks(w) == 9, "clear_frame");
    c.clearWrites();
    d.clear_all_frames();
    check_true(count_pwm_chunks(w) == 72, "clear_all_frames");
    d.select_frame(0);

    c.clearWrites();
    d.show_frame(4);
    check_true(w.size() == 2 && eq(w[0], {0xFD, 0x0B}) && eq(w[1], {0x00, 0x00, 0x04}), "show_frame");
    d.set_picture_mode();
    check_true(eq(w.back(), {0x00, 0x00}), "picture_mode");

    d.start_auto_play(3, 2, 121, 1);
    check_true(eq(w[w.size() - 2], {0x02, 0x23, 11}) && eq(w.back(), {0x00, 0x09}), "auto_play");
    d.start_auto_play();
    check_true(eq(w[w.size() - 2], {0x02, 0x00, 11}) && eq(w.back(), {0x00, 0x08}), "auto_play_defaults");
    d.start_auto_play(0, 0, 704);
    check_true(eq(w[w.size() - 2], {0x02, 0x00, 0x00}), "auto_play_64");
    check_true(!d.start_auto_play(0, 0, 2000) && !d.start_auto_play(0, 8), "auto_play_range");
    c.setRegister(0x07, {0x15});
    d.stop_auto_play();
    check_true(eq(w.back(), {0x00, 0x00, 0x05}), "stop_auto_play");

    d.set_audio_mode(true);
    check_true(eq(w.back(), {0x00, 0x10}), "audio_mode");
    d.set_audio_mode(false);
    check_true(eq(w.back(), {0x00, 0x00}), "audio_mode_off");
    d.set_audio_sync(true);
    check_true(eq(w.back(), {0x06, 0x01}), "audio_sync");
    d.set_audio_gain(9, true, true);
    check_true(eq(w.back(), {0x0B, 0x1B}), "audio_gain");
    check_true(!d.set_audio_gain(4) && !d.set_audio_gain(24), "audio_gain_range");
    d.set_audio_rate(100);
    check_true(eq(w.back(), {0x0C, 100}), "audio_rate");

    d.enable_blink(true, 3);
    check_true(eq(w.back(), {0x05, 0x0B}), "enable_blink");
    d.set_intensity_control(true);
    check_true(eq(w.back(), {0x05, 0x2B}), "intensity_control");
    d.enable_blink(false);
    check_true(eq(w.back(), {0x05, 0x21}), "disable_blink");
    check_true(!d.enable_blink(true, 8), "blink_period_range");
    d.set_breath(true, 2, 5, 3);
    check_true(eq(w.back(), {0x08, 0x52, 0x13}), "breath");
    d.set_breath(false);
    check_true(eq(w.back(), {0x08, 0x00, 0x00}), "breath_off");

    d.shutdown();
    check_true(eq(w.back(), {0x0A, 0x00}), "shutdown");
    d.shutdown(false);
    check_true(eq(w.back(), {0x0A, 0x01}), "shutdown_off");

    c.setRegister(0x07, {0x13});
    check_true(d.frame_state() == 0x13 && d.current_frame() == 3 && d.movie_finished(), "frame_state");
    c.setRegister(0x07, {0x02});
    check_true(!d.movie_finished(), "movie_not_finished");

    d.select_frame(2);
    c.clearWrites();
    c.setRegister(0x03, {0x2A});
    check_true(d.read_function_register(0x03) == 0x2A && eq(w.back(), {0xFD, 0x02}), "read_function_register");
    c.clearWrites();
    d.write_function_register(0x03, 5);
    check_true(w.size() == 3 && eq(w[0], {0xFD, 0x0B}) && eq(w[1], {0x03, 5}) && eq(w[2], {0xFD, 0x02}), "write_function_register");
    check_true(!d.write_function_register(0x0D, 0) && d.read_function_register(0x0D) == 0, "function_register_range");

    c.clearWrites();
    c.setRegister(0x07, {0x14});
    check_true(d.poll_interrupt() == 0x14 && eq(w.back(), {0xFD, 0x02}), "poll_interrupt");

    check_true(Is31fl3731Full::GAMMA_32[31] == 255 && Is31fl3731Full::GAMMA_64[63] == 255
               && Is31fl3731Full::GAMMA_64[8] == 8 && Is31fl3731Full::GAMMA_64[9] == 10, "gamma_tables");

    I2CConnectionMock c2;
    Is31fl3731Minimal m(c2);
    check_true(m.set_pixel(0, 0, 1) && Is31fl3731Minimal::ROWS == 9 && Is31fl3731Minimal::COLS == 16, "minimal");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
