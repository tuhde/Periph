#include <stdio.h>
#include "I2CConnectionMock.h"
#include "SK6812RGBW.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// SK6812RGBW is write-only, same reasoning as WS2812B (see
// ws2812b_test_unit.cpp) - the generic I2CConnectionMock works unchanged;
// only its writes() list is used. This chip additionally appends 24
// zero-bytes (~80us reset) after the pixel buffer on every transmit.

static const size_t N = 3;

static bool allZero(const std::vector<uint8_t>& v, size_t from, size_t to) {
    for (size_t i = from; i < to; i++) if (v[i] != 0) return false;
    return true;
}

int main() {
    I2CConnectionMock connection;
    SK6812RGBWFull sensor(connection, N);
    check_true(true, "init");

    // fill(): GRBW wire order, all N pixels, transmits immediately, with
    // the 24-byte extended reset tail appended.
    sensor.fill(0x11, 0x22, 0x33, 0x44);
    check_true(connection.writes().size() == 1, "fill_transmits");
    check_true(connection.writes().back().size() == N * 4 + 24, "fill_length");
    {
        const auto& w = connection.writes().back();
        check_true(w[0] == 0x22 && w[1] == 0x11 && w[2] == 0x33 && w[3] == 0x44, "fill_grbw_order");
        bool allPixels = true;
        for (size_t i = 0; i < N; i++) {
            if (w[i*4] != 0x22 || w[i*4+1] != 0x11 || w[i*4+2] != 0x33 || w[i*4+3] != 0x44) allPixels = false;
        }
        check_true(allPixels, "fill_all_pixels");
        check_true(allZero(w, N * 4, N * 4 + 24), "fill_reset_tail");
    }

    // fill() white channel defaults to 0.
    sensor.fill(0x10, 0x20, 0x30);
    {
        const auto& w = connection.writes().back();
        check_true(w[0] == 0x20 && w[1] == 0x10 && w[2] == 0x30 && w[3] == 0x00, "fill_white_defaults_zero");
    }

    // off(): equivalent to fill(0, 0, 0, 0).
    sensor.off();
    {
        const auto& w = connection.writes().back();
        check_true(allZero(w, 0, N * 4), "off");
        check_true(allZero(w, N * 4, N * 4 + 24), "off_reset_tail");
    }

    // set_pixel(): buffer-only, no transmit; white channel defaults to 0.
    size_t writesBefore = connection.writes().size();
    sensor.set_pixel(1, 0xAA, 0xBB, 0xCC, 0xDD);
    check_true(connection.writes().size() == writesBefore, "set_pixel_no_transmit");
    sensor.show();
    {
        const auto& w = connection.writes().back();
        check_true(w[4] == 0xBB && w[5] == 0xAA && w[6] == 0xCC && w[7] == 0xDD, "set_pixel_then_show");
        check_true(w[0] == 0 && w[1] == 0 && w[2] == 0 && w[3] == 0, "set_pixel_other_pixels_unchanged");
    }

    sensor.set_pixel(0, 0x01, 0x02, 0x03);
    sensor.show();
    {
        const auto& w = connection.writes().back();
        check_true(w[0] == 0x02 && w[1] == 0x01 && w[2] == 0x03 && w[3] == 0x00, "set_pixel_white_defaults_zero");
    }

    // set_pixel() clamps index to [0, n-1].
    sensor.set_pixel(99, 0x05, 0x06, 0x07, 0x08);
    sensor.show();
    {
        const auto& w = connection.writes().back();
        check_true(w[(N-1)*4] == 0x06 && w[(N-1)*4+1] == 0x05 && w[(N-1)*4+2] == 0x07 && w[(N-1)*4+3] == 0x08,
                   "set_pixel_clamps_index");
    }

    // brightness scaling at show() time: sent = stored * brightness / 255.
    sensor.set_brightness(128);
    sensor.set_pixel(0, 200, 100, 50, 40);
    sensor.set_pixel(1, 0, 0, 0, 0);
    sensor.set_pixel(2, 0, 0, 0, 0);
    sensor.show();
    {
        const auto& w = connection.writes().back();
        uint8_t sg = (uint8_t)((uint16_t)100 * 128 / 255);
        uint8_t sr = (uint8_t)((uint16_t)200 * 128 / 255);
        uint8_t sb = (uint8_t)((uint16_t)50  * 128 / 255);
        uint8_t sw = (uint8_t)((uint16_t)40  * 128 / 255);
        check_true(w[0] == sg && w[1] == sr && w[2] == sb && w[3] == sw, "brightness_scaling");
    }
    sensor.set_brightness(255);

    // rotate(): shifts pixel buffer left by `steps` whole (4-byte) pixels, no transmit.
    sensor.set_pixel(0, 1, 0, 0, 0);
    sensor.set_pixel(1, 2, 0, 0, 0);
    sensor.set_pixel(2, 3, 0, 0, 0);
    sensor.show();
    writesBefore = connection.writes().size();
    sensor.rotate(1);
    check_true(connection.writes().size() == writesBefore, "rotate_no_transmit");
    sensor.show();
    {
        const auto& w = connection.writes().back();
        check_true(w[1] == 2 && w[1 + 2*4] == 1, "rotate_shifts_left");
    }

    // fill_hsv(): pure red (h=0, s=1, v=1) -> RGB (255, 0, 0), white=0.
    sensor.fill_hsv(0.0f, 1.0f, 1.0f);
    {
        const auto& w = connection.writes().back();
        check_true(w[0] == 0 && w[1] == 255 && w[2] == 0 && w[3] == 0, "fill_hsv_red");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
