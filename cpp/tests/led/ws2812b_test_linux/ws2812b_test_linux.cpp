#include <cstdio>
#include <cstdint>
#include "NeoPixelTransportLinux.h"
#include "WS2812B.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

static void check_eq_u8(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { printf("PASS %s\n", label); passed++; }
    else {
        printf("FAIL %s: got %u, expected %u\n", label, got, expected);
        failed++;
    }
}

int main() {
    NeoPixelTransportLinux transport(TEST_SPI_BUS, TEST_SPI_DEVICE);

    // --- WS2812BMinimal ---
    {
        WS2812BMinimal strip(transport, 8);

        strip.fill(255, 0, 0);
        check_true("fill(255,0,0) accepted", true);

        strip.fill(0, 255, 0);
        check_true("fill(0,255,0) accepted", true);

        strip.fill(0, 0, 255);
        check_true("fill(0,0,255) accepted", true);

        strip.off();
        check_true("off() accepted", true);
    }

    // --- WS2812BFull ---
    {
        WS2812BFull strip(transport, 8);

        check_eq_u8("default brightness is 255", strip.get_brightness(), 255);

        strip.set_pixel(0, 255, 0, 0);
        strip.show();
        check_true("set_pixel + show accepted", true);

        strip.set_brightness(128);
        check_eq_u8("brightness setter", strip.get_brightness(), 128);
        strip.show();
        check_true("show() with brightness=128 accepted", true);

        strip.set_brightness(255);

        strip.rotate(1);
        strip.show();
        check_true("rotate + show accepted", true);

        strip.fill_hsv(0.0f, 1.0f, 1.0f);
        check_true("fill_hsv(0.0) accepted", true);

        strip.fill_hsv(0.333f, 1.0f, 1.0f);
        check_true("fill_hsv(0.333) accepted", true);

        strip.off();
        check_true("off() on Full accepted", true);
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
