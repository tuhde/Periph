#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "NeoPixelTransportZephyr.h"
#include "WS2812B.h"

#define SPI_NODE DT_NODELABEL(spi0)

static const size_t N_PIXELS       = 30;
static const uint32_t FRAME_MS     = 33;
static const uint32_t RAINBOW_MS   = 10000;
static const uint32_t STROBE_MS    = 2000;
static const uint32_t STROBE_HALF  = 50;

static void hsv_to_rgb(float h, float s, float v,
                        uint8_t &r, uint8_t &g, uint8_t &b);

int main(void) {
    const struct device *spi_dev = DEVICE_DT_GET(SPI_NODE);
    NeoPixelTransportZephyr transport(spi_dev);    // Create NeoPixel transport, (dev=spi_device*)
    WS2812BFull strip(transport, N_PIXELS);        // Create WS2812B full driver, (transport, n=N_PIXELS pixels)
    strip.set_brightness(180);                     // Set global brightness, (value=0–255) → void

    while (1) {
        // --- Rainbow rotation: each pixel is assigned a hue offset by its position;
        //     the offset is advanced each frame so the rainbow rotates around the strip.
        //     Running at ~30 fps for 10 seconds gives a smooth continuous animation. ---
        float hue_offset = 0.0f;
        uint32_t start = k_uptime_get_32();
        while (k_uptime_get_32() - start < RAINBOW_MS) {
            for (size_t i = 0; i < N_PIXELS; i++) {
                float h = fmodf(hue_offset + (float)i / N_PIXELS, 1.0f);
                uint8_t r, g, b;
                hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
                strip.set_pixel(i, r, g, b);       // Set pixel i to rainbow hue, (index=0–n-1, r=0–255, g=0–255, b=0–255) → void
            }
            strip.show();                          // Transmit buffer to strip, () → void
            hue_offset = fmodf(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
            k_sleep(K_MSEC(FRAME_MS));
        }

        // --- Strobe effect: alternate full white and off at 10 Hz for 2 seconds.
        //     Uses brightness=255 for maximum intensity then brightness=0 for off,
        //     demonstrating non-destructive brightness scaling — pixel values in the
        //     buffer are never zeroed. ---
        strip.set_brightness(255);                 // Set global brightness, (value=0–255) → void
        strip.fill(255, 255, 255);                 // Pre-load white into buffer, (r=0–255, g=0–255, b=0–255) → void
        start = k_uptime_get_32();
        bool state = true;
        while (k_uptime_get_32() - start < STROBE_MS) {
            strip.set_brightness(state ? 255 : 0); // Set global brightness, (value=0–255) → void
            strip.show();                          // Transmit buffer to strip, () → void
            state = !state;
            k_sleep(K_MSEC(STROBE_HALF));
        }

        // --- Return to rainbow ---
        strip.set_brightness(180);                 // Set global brightness, (value=0–255) → void
    }
    return 0;
}

static void hsv_to_rgb(float h, float s, float v,
                        uint8_t &r, uint8_t &g, uint8_t &b) {
    if (s == 0.0f) { r = g = b = (uint8_t)(v * 255); return; }
    int i = (int)(h * 6.0f);
    float f = h * 6.0f - (float)i;
    uint8_t p  = (uint8_t)(v * (1.0f - s) * 255);
    uint8_t q  = (uint8_t)(v * (1.0f - s * f) * 255);
    uint8_t t  = (uint8_t)(v * (1.0f - s * (1.0f - f)) * 255);
    uint8_t vv = (uint8_t)(v * 255);
    switch (i % 6) {
        case 0: r = vv; g = t;  b = p;  return;
        case 1: r = q;  g = vv; b = p;  return;
        case 2: r = p;  g = vv; b = t;  return;
        case 3: r = p;  g = q;  b = vv; return;
        case 4: r = t;  g = p;  b = vv; return;
        default: r = vv; g = p; b = q;
    }
}
