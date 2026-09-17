#include <stdio.h>
#include "I2CConnectionMock.h"
#include "APA102.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// APA102 is write-only (no registers, no reads) - the connection mock only
// ever needs to record what bytes were sent, so the same generic
// I2CConnectionMock used by every register-addressed chip works unchanged
// here too; only its writes() list is used.

static const size_t N = 4;

int main() {
    I2CConnectionMock connection;
    APA102Full sensor(connection, N);
    check_true(true, "init");

    // Expected frame structure:
    // start_frame = bytes(4)                    # 0x00 × 4
    // pixel data: N × 4 bytes [0xE0|brightness, B, G, R]
    // end_frame = bytes([0xFF] * max(4, (N + 15) // 16))
    uint8_t end_bytes = (N + 15) / 16 < 4 ? 4 : (N + 15) / 16;
    size_t FRAME_LEN = 4 + N * 4 + end_bytes;

    // fill(): BGR wire order with brightness byte, all N pixels, transmits immediately.
    sensor.fill(0x11, 0x22, 0x33);
    check_true(connection.writes().size() == 1, "fill_transmits");
    check_true(connection.writes().back().size() == FRAME_LEN, "fill_length");

    // Check start frame (4 zero bytes)
    const auto& w0 = connection.writes().back();
    bool start_ok = (w0[0] == 0 && w0[1] == 0 && w0[2] == 0 && w0[3] == 0);
    check_true(start_ok, "fill_start_frame");

    // Check pixel data: [0xE0|31, B, G, R] = [0xFF, 0x33, 0x22, 0x11] per pixel
    bool pixel_ok = true;
    for (size_t i = 0; i < N; i++) {
        size_t base = 4 + i * 4;
        if (w0[base] != 0xFF || w0[base + 1] != 0x33 || w0[base + 2] != 0x22 || w0[base + 3] != 0x11) {
            pixel_ok = false;
        }
    }
    check_true(pixel_ok, "fill_pixel_order");

    // Check end frame (all 0xFF)
    bool end_ok = true;
    for (size_t i = 0; i < end_bytes; i++) {
        if (w0[4 + N * 4 + i] != 0xFF) end_ok = false;
    }
    check_true(end_ok, "fill_end_frame");

    // fill() clamps out-of-range channels.
    sensor.fill(-10, 300, 128);
    const auto& w1 = connection.writes().back();
    // Expected: [0xFF, 255, 0, 128] per pixel (B=255 clamped, G=0 clamped, R=128)
    bool clamp_ok = true;
    for (size_t i = 0; i < N; i++) {
        size_t base = 4 + i * 4;
        if (w1[base] != 0xFF || w1[base + 1] != 255 || w1[base + 2] != 0 || w1[base + 3] != 128) {
            clamp_ok = false;
        }
    }
    check_true(clamp_ok, "fill_clamps");

    // off(): equivalent to fill(0, 0, 0).
    sensor.off();
    const auto& w2 = connection.writes().back();
    // Expected: [0xFF, 0, 0, 0] per pixel (brightness=31, B=0, G=0, R=0)
    bool off_ok = true;
    for (size_t i = 0; i < N; i++) {
        size_t base = 4 + i * 4;
        if (w2[base] != 0xFF || w2[base + 1] != 0 || w2[base + 2] != 0 || w2[base + 3] != 0) {
            off_ok = false;
        }
    }
    check_true(off_ok, "off");

    // set_pixel(): buffer-only, no transmit.
    size_t writesBefore = connection.writes().size();
    sensor.set_pixel(1, 0xAA, 0xBB, 0xCC);
    check_true(connection.writes().size() == writesBefore, "set_pixel_no_transmit");
    sensor.show();
    const auto& w3 = connection.writes().back();
    // Pixel 1 should be [0xFF, 0xCC, 0xBB, 0xAA]
    check_true(w3[4 + 4] == 0xFF && w3[4 + 5] == 0xCC && w3[4 + 6] == 0xBB && w3[4 + 7] == 0xAA, "set_pixel_then_show");
    // Pixel 0 should be [0xFF, 0, 0, 0]
    check_true(w3[4] == 0xFF && w3[5] == 0 && w3[6] == 0 && w3[7] == 0, "set_pixel_other_pixels_zero");

    // set_pixel() clamps index to [0, n-1].
    sensor.set_pixel(99, 0x01, 0x02, 0x03);
    sensor.show();
    const auto& w4 = connection.writes().back();
    check_true(w4[4 + (N - 1) * 4] == 0xFF && w4[4 + (N - 1) * 4 + 1] == 0x03 && w4[4 + (N - 1) * 4 + 2] == 0x02 && w4[4 + (N - 1) * 4 + 3] == 0x01, "set_pixel_clamps_index");

    // set_pixel() with custom pixel_brightness.
    sensor.set_pixel(0, 0x10, 0x20, 0x30, 16);  // brightness=16
    sensor.show();
    const auto& w5 = connection.writes().back();
    check_true(w5[4] == (0xE0 | 16) && w5[5] == 0x30 && w5[6] == 0x20 && w5[7] == 0x10, "set_pixel_hardware_brightness");

    // set_pixels(): sequence of (r, g, b) or (r, g, b, brightness), extras beyond n ignored.
    uint8_t colors[] = {
        0x10, 0x20, 0x30,   0x40, 0x50, 0x60,   0x70, 0x80, 0x90,   0xA0, 0xB0, 0xC0,   0xFF, 0xFF, 0xFF
    };
    sensor.set_pixels(colors, 5, false);
    sensor.show();
    const auto& w6 = connection.writes().back();
    bool set_pixels_ok = true;
    // pixel 0: [0xFF, 0x30, 0x20, 0x10]
    if (w6[4] != 0xFF || w6[5] != 0x30 || w6[6] != 0x20 || w6[7] != 0x10) set_pixels_ok = false;
    // pixel 1: [0xFF, 0x60, 0x50, 0x40]
    if (w6[8] != 0xFF || w6[9] != 0x60 || w6[10] != 0x50 || w6[11] != 0x40) set_pixels_ok = false;
    // pixel 2: [0xFF, 0x90, 0x80, 0x70]
    if (w6[12] != 0xFF || w6[13] != 0x90 || w6[14] != 0x80 || w6[15] != 0x70) set_pixels_ok = false;
    // pixel 3: [0xFF, 0xC0, 0xB0, 0xA0]
    if (w6[16] != 0xFF || w6[17] != 0xC0 || w6[18] != 0xB0 || w6[19] != 0xA0) set_pixels_ok = false;
    check_true(set_pixels_ok, "set_pixels");

    // set_pixels() with per-pixel brightness.
    uint8_t colors_bright[] = {
        0x10, 0x20, 0x30, 31,   0x40, 0x50, 0x60, 16,   0x70, 0x80, 0x90, 8,   0xA0, 0xB0, 0xC0, 4
    };
    sensor.set_pixels(colors_bright, 4, true);
    sensor.show();
    const auto& w7 = connection.writes().back();
    bool set_pixels_bright_ok = true;
    // pixel 0: [0xFF, 0x30, 0x20, 0x10]
    if (w7[4] != 0xFF || w7[5] != 0x30 || w7[6] != 0x20 || w7[7] != 0x10) set_pixels_bright_ok = false;
    // pixel 1: [0xF0, 0x60, 0x50, 0x40]
    if (w7[8] != 0xF0 || w7[9] != 0x60 || w7[10] != 0x50 || w7[11] != 0x40) set_pixels_bright_ok = false;
    // pixel 2: [0xE8, 0x90, 0x80, 0x70]
    if (w7[12] != 0xE8 || w7[13] != 0x90 || w7[14] != 0x80 || w7[15] != 0x70) set_pixels_bright_ok = false;
    // pixel 3: [0xE4, 0xC0, 0xB0, 0xA0]
    if (w7[16] != 0xE4 || w7[17] != 0xC0 || w7[18] != 0xB0 || w7[19] != 0xA0) set_pixels_bright_ok = false;
    check_true(set_pixels_bright_ok, "set_pixels_hardware_brightness");

    // brightness scaling at show() time: sent = stored * brightness / 255.
    // Hardware brightness byte is NOT scaled.
    sensor.set_brightness(128);
    sensor.set_pixel(0, 200, 100, 50);  // stored: [0xFF, 50, 100, 200]
    sensor.set_pixel(1, 0, 0, 0);
    sensor.set_pixel(2, 0, 0, 0);
    sensor.set_pixel(3, 0, 0, 0);
    sensor.show();
    const auto& w8 = connection.writes().back();
    uint8_t scaled_r = (uint16_t)200 * 128 / 255;
    uint8_t scaled_g = (uint16_t)100 * 128 / 255;
    uint8_t scaled_b = (uint16_t)50 * 128 / 255;
    check_true(w8[4] == 0xFF && w8[5] == scaled_b && w8[6] == scaled_g && w8[7] == scaled_r, "brightness_scaling");
    // Other pixels unchanged (still zero)
    check_true(w8[8] == 0xFF && w8[9] == 0 && w8[10] == 0 && w8[11] == 0, "brightness_other_pixels_zero");
    check_true(sensor.get_brightness() == 128, "get_brightness");
    sensor.set_brightness(255);

    // rotate(): shifts pixel buffer left by `steps` whole pixels, no transmit.
    sensor.set_pixel(0, 1, 0, 0);
    sensor.set_pixel(1, 2, 0, 0);
    sensor.set_pixel(2, 3, 0, 0);
    sensor.set_pixel(3, 4, 0, 0);
    sensor.show();
    writesBefore = connection.writes().size();
    sensor.rotate(1);
    check_true(connection.writes().size() == writesBefore, "rotate_no_transmit");
    sensor.show();
    const auto& w9 = connection.writes().back();
    // After rotate(1): pixel 0 gets old pixel 1 (R=2), pixel 3 gets old pixel 0 (R=1)
    check_true(w9[4 + 0 + 3] == 2 && w9[4 + (N - 1) * 4 + 3] == 1, "rotate_shifts_left");

    // fill_hsv(): pure red (h=0, s=1, v=1) -> RGB (255, 0, 0).
    sensor.fill_hsv(0.0f, 1.0f, 1.0f);
    const auto& w10 = connection.writes().back();
    // Red=255, Green=0, Blue=0 -> wire: [0xFF, 0, 0, 255]
    bool hsv_ok = true;
    for (size_t i = 0; i < N; i++) {
        size_t base = 4 + i * 4;
        if (w10[base] != 0xFF || w10[base + 1] != 0 || w10[base + 2] != 0 || w10[base + 3] != 255) {
            hsv_ok = false;
        }
    }
    check_true(hsv_ok, "fill_hsv_red");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}