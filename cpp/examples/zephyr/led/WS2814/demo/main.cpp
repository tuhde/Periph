#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "NeoPixelConnectionZephyr.h"
#include "WS2814.h"

#define SPI_NODE DT_NODELABEL(spi0)

static const size_t N_PIXELS       = 30;
static const uint32_t FRAME_MS     = 33;
static const uint32_t RAINBOW_MS   = 5000;
static const uint32_t FLASH_MS     = 2000;
static const uint32_t DIM_MS       = 2000;

int main(void) {
    const struct device *spi_dev = DEVICE_DT_GET(SPI_NODE);
    NeoPixelConnectionZephyr connection(spi_dev);    // Create NeoPixel connection, (dev=spi_device*)
    WS2814Full strip(connection, N_PIXELS);         // Create WS2814 full driver, (connection, n=N_PIXELS pixels)

    while (1) {
        // --- Rainbow rotation using RGB channels (white=0). Each pixel is
        //     assigned a hue offset by its position; the offset advances each
        //     frame so the rainbow rotates continuously around the strip.
        //     Demonstrates that WS2814's RGBW wire order is identity (R, G, B,
        //     W with no reorder), unlike the SK6812RGBW's GRBW order. Runs at
        //     ~30 fps for 5 seconds. ---
        float hue_offset = 0.0f;
        uint32_t start = k_uptime_get_32();
        uint32_t last_print = start;
        while (k_uptime_get_32() - start < RAINBOW_MS) {
            for (size_t i = 0; i < N_PIXELS; i++) {
                float h = fmodf(hue_offset + (float)i / N_PIXELS, 1.0f);
                uint8_t r, g, b;
                neopixel_hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
                strip.set_pixel(i, r, g, b, 0);    // Set pixel i to rainbow hue (w=0), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
            }
            strip.show();                          // Transmit buffer to strip, () → void
            hue_offset = fmodf(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
            uint32_t now = k_uptime_get_32();
            if (now - last_print >= 1000) {
                printk("mode=rainbow brightness=%u\n", strip.get_brightness());
                last_print = now;
            }
            k_sleep(K_MSEC(FRAME_MS));
        }

        // --- Warm white at full brightness for 2 seconds. r=255, g=200,
        //     b=150, w=255 blends the dedicated white element with
        //     amber-tinted RGB, exercising the white channel and the 32-bit
        //     RGBW pixel word at full brightness. ---
        strip.fill(255, 200, 150, 255);            // Fill all pixels warm white, (r=0–255, g=0–255, b=0–255, w=0–255) → void
        start = k_uptime_get_32();
        while (k_uptime_get_32() - start < FLASH_MS) {
            printk("mode=warm-white brightness=%u\n", strip.get_brightness());
            k_sleep(K_MSEC(100));
        }

        // --- Dim warm white to 50% using the brightness property and hold
        //     for 2 seconds. Demonstrates that brightness scaling is
        //     non-destructive: the stored RGBW values are unchanged, only
        //     the scale factor applied at show() time changes. ---
        strip.set_brightness(128);                 // Set global brightness, (value=0–255) → void
        strip.show();                              // Transmit buffer to strip, () → void
        start = k_uptime_get_32();
        while (k_uptime_get_32() - start < DIM_MS) {
            printk("mode=warm-white-dimmed brightness=%u\n", strip.get_brightness());
            k_sleep(K_MSEC(100));
        }

        // --- Cycle to cool white at full brightness. r=200, g=210, b=255,
        //     w=255 shifts the blend toward blue, showcasing the dedicated
        //     white element paired with a cool-tinted RGB base. ---
        strip.set_brightness(255);                 // Set global brightness, (value=0–255) → void
        strip.fill(200, 210, 255, 255);            // Fill all pixels cool white, (r=0–255, g=0–255, b=0–255, w=0–255) → void
        printk("mode=cool-white brightness=%u\n", strip.get_brightness());
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
