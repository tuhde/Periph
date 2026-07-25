#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "NeoPixelTransportZephyr.h"
#include "SK6812RGBW.h"

#define SPI_NODE DT_NODELABEL(spi0)

static const size_t N_PIXELS       = 30;
static const uint32_t FRAME_MS     = 33;
static const uint32_t RAINBOW_MS   = 10000;
static const uint32_t WARM_MS      = 2000;
static const uint32_t WARM_HALF    = 100;

int main(void) {
    const struct device *spi_dev = DEVICE_DT_GET(SPI_NODE);
    NeoPixelTransportZephyr transport(spi_dev);    // Create NeoPixel transport, (dev=spi_device*)
    SK6812RGBWFull strip(transport, N_PIXELS);     // Create SK6812RGBW full driver, (transport, n=N_PIXELS pixels)
    strip.set_brightness(180);                     // Set global brightness, (value=0–255) → void

    while (1) {
        // --- Rainbow rotation: each pixel is assigned a hue offset by its position;
        //     the offset advances each frame so the rainbow rotates around the strip.
        //     RGB channels only (w=0) for 10 seconds at ~30 fps. ---
        float hue_offset = 0.0f;
        uint32_t start = k_uptime_get_32();
        while (k_uptime_get_32() - start < RAINBOW_MS) {
            for (size_t i = 0; i < N_PIXELS; i++) {
                float h = fmodf(hue_offset + (float)i / N_PIXELS, 1.0f);
                uint8_t r, g, b;
                neopixel_hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
                strip.set_pixel(i, r, g, b, 0);    // Set pixel i to rainbow hue (w=0), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
            }
            strip.show();                          // Transmit buffer to strip, () → void
            hue_offset = fmodf(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
            k_sleep(K_MSEC(FRAME_MS));
        }

        // --- Warm-white strobe: showcases the dedicated white element.
        //     All four channels active (r=255, g=200, b=150, w=255) gives a warm,
        //     high-CRI white; toggling at 5 Hz for 2 seconds draws the eye to the
        //     difference between mixed-RGB white and the native W element. ---
        strip.set_brightness(255);                 // Set global brightness, (value=0–255) → void
        strip.fill(255, 200, 150, 255);            // Pre-load warm white (RGB+W) into buffer, (r=0–255, g=0–255, b=0–255, w=0–255) → void
        start = k_uptime_get_32();
        bool state = true;
        while (k_uptime_get_32() - start < WARM_MS) {
            strip.set_brightness(state ? 255 : 0); // Toggle brightness on/off, (value=0–255) → void
            strip.show();                          // Transmit buffer to strip, () → void
            state = !state;
            k_sleep(K_MSEC(WARM_HALF));
        }

        // --- Return to rainbow ---
        strip.set_brightness(180);                 // Set global brightness, (value=0–255) → void
    }
    return 0;
}
