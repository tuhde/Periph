#include <stdio.h>
#include "I2CConnectionMock.h"
#include "WS2812B.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// WS2812B is write-only (no registers, no reads) - the connection mock only
// ever needs to record what bytes were sent, so the same generic
// I2CConnectionMock used by every register-addressed chip works unchanged
// here too; only its writes() list is used.

static const size_t N = 4;

int main() {
    I2CConnectionMock connection;
    WS2812BFull sensor(connection, N);
    check_true(true, "init");

    // fill(): GRB wire order, all N pixels, transmits immediately.
    sensor.fill(0x11, 0x22, 0x33);
    check_true(connection.writes().size() == 1, "fill_transmits");
    check_true(connection.writes().back().size() == N * 3, "fill_length");
    {
        const auto& w = connection.writes().back();
        check_true(w[0] == 0x22 && w[1] == 0x11 && w[2] == 0x33, "fill_grb_order");
        bool allPixels = true;
        for (size_t i = 0; i < N; i++) {
            if (w[i*3] != 0x22 || w[i*3+1] != 0x11 || w[i*3+2] != 0x33) allPixels = false;
        }
        check_true(allPixels, "fill_all_pixels");
    }

    // off(): equivalent to fill(0, 0, 0).
    sensor.off();
    {
        const auto& w = connection.writes().back();
        bool allZero = true;
        for (auto b : w) if (b != 0) allZero = false;
        check_true(allZero, "off");
    }

    // set_pixel(): buffer-only, no transmit.
    size_t writesBefore = connection.writes().size();
    sensor.set_pixel(1, 0xAA, 0xBB, 0xCC);
    check_true(connection.writes().size() == writesBefore, "set_pixel_no_transmit");
    sensor.show();
    {
        const auto& w = connection.writes().back();
        check_true(w[3] == 0xBB && w[4] == 0xAA && w[5] == 0xCC, "set_pixel_then_show");
        check_true(w[0] == 0 && w[1] == 0 && w[2] == 0, "set_pixel_other_pixels_unchanged");
    }

    // set_pixel() clamps index to [0, n-1].
    sensor.set_pixel(99, 0x01, 0x02, 0x03);
    sensor.show();
    {
        const auto& w = connection.writes().back();
        check_true(w[(N-1)*3] == 0x02 && w[(N-1)*3+1] == 0x01 && w[(N-1)*3+2] == 0x03,
                   "set_pixel_clamps_index");
    }

    // brightness scaling at show() time: sent = stored * brightness / 255.
    sensor.set_brightness(128);
    sensor.set_pixel(0, 200, 100, 50);
    sensor.set_pixel(1, 0, 0, 0);
    sensor.set_pixel(2, 0, 0, 0);
    sensor.set_pixel(3, 0, 0, 0);
    sensor.show();
    {
        const auto& w = connection.writes().back();
        uint8_t scaledR = (uint8_t)((uint16_t)200 * 128 / 255);
        uint8_t scaledG = (uint8_t)((uint16_t)100 * 128 / 255);
        uint8_t scaledB = (uint8_t)((uint16_t)50 * 128 / 255);
        check_true(w[0] == scaledG && w[1] == scaledR && w[2] == scaledB, "brightness_scaling");
    }
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
    {
        const auto& w = connection.writes().back();
        check_true(w[1] == 2 && w[1 + 3*3] == 1, "rotate_shifts_left");
    }

    // fill_hsv(): pure red (h=0, s=1, v=1) -> RGB (255, 0, 0).
    sensor.fill_hsv(0.0f, 1.0f, 1.0f);
    {
        const auto& w = connection.writes().back();
        check_true(w[0] == 0 && w[1] == 255 && w[2] == 0, "fill_hsv_red");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
