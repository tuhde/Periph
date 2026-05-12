#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "NeoPixelTransportZephyr.h"
#include "WS2812B.h"

#ifndef WS2812B_SPI_NODE
#define WS2812B_SPI_NODE DT_NODELABEL(spi0)
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

static void check_eq_u8(const char *label, uint8_t got, uint8_t expected) {
    if (got == expected) { printk("PASS %s\n", label); passed++; }
    else { printk("FAIL %s: got %u, expected %u\n", label, got, expected); failed++; }
}

int main(void) {
    const struct device *spi_dev = DEVICE_DT_GET(WS2812B_SPI_NODE);
    NeoPixelTransportZephyr transport(spi_dev);

    // --- WS2812BMinimal ---
    {
        WS2812BMinimal strip(transport, 8);

        strip.fill(255, 0, 0);
        check_true(true, "fill(255,0,0) accepted");

        strip.fill(0, 255, 0);
        check_true(true, "fill(0,255,0) accepted");

        strip.off();
        check_true(true, "off() accepted");
    }

    // --- WS2812BFull ---
    {
        WS2812BFull strip(transport, 8);

        check_eq_u8("default brightness is 255", strip.get_brightness(), 255);

        strip.set_pixel(0, 255, 0, 0);
        strip.show();
        check_true(true, "set_pixel + show accepted");

        strip.set_brightness(128);
        check_eq_u8("brightness setter", strip.get_brightness(), 128);
        strip.show();
        check_true(true, "show() with brightness=128 accepted");

        strip.set_brightness(255);

        strip.rotate(1);
        strip.show();
        check_true(true, "rotate + show accepted");

        strip.fill_hsv(0.0f, 1.0f, 1.0f);
        check_true(true, "fill_hsv accepted");

        strip.off();
        check_true(true, "off() on Full accepted");
    }

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
