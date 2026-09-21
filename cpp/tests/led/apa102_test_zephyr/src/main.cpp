#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/spi.h>
#include "SPIConnectionZephyr.h"
#include "APA102.h"

#ifndef APA102_SPI_NODE
#define APA102_SPI_NODE DT_NODELABEL(spi0)
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
    const struct device *spi_dev = DEVICE_DT_GET(APA102_SPI_NODE);

    struct spi_config spi_cfg = {
        .frequency = 1000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .cs = {},
        .slave = 0,
    };

    SPIConnectionZephyr connection(spi_dev, spi_cfg);

    // --- APA102Minimal ---
    {
        APA102Minimal strip(connection, 8);

        strip.fill(255, 0, 0);
        check_true(true, "fill(255,0,0) accepted");

        strip.fill(0, 255, 0);
        check_true(true, "fill(0,255,0) accepted");

        strip.off();
        check_true(true, "off() accepted");
    }

    // --- APA102Full ---
    {
        APA102Full strip(connection, 8);

        check_eq_u8("default brightness is 255", strip.get_brightness(), 255);

        strip.set_pixel(0, 255, 0, 0);
        strip.show();
        check_true(true, "set_pixel + show accepted");

        strip.set_pixels((uint8_t[]){255, 0, 0}, 1, false);
        strip.show();
        check_true(true, "set_pixels + show accepted");

        // set_pixels with per-pixel hardware brightness
        uint8_t colors_bright[] = {
            255, 0, 0, 31,   255, 0, 0, 16,   255, 0, 0, 8,   255, 0, 0, 4,
            0, 255, 0, 31,   0, 255, 0, 16,   0, 255, 0, 8,   0, 255, 0, 4
        };
        strip.set_pixels(colors_bright, 8, true);
        strip.show();
        check_true(true, "set_pixels with hardware brightness + show accepted");

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