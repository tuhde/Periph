#include <cstdio>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "APA102.h"

static const size_t N_PIXELS          = 30;
static const unsigned long FRAME_US   = 16666;  // ~60 fps
static const unsigned long RAINBOW_US = 10000000;

static void hsv_to_rgb(float h, float s, float v,
                        uint8_t& r, uint8_t& g, uint8_t& b);

int main() {
    SPIConnectionLinux connection(0, 0, 0, 1000000);                        // Create SPI connection, (bus=0, device=0, mode=0, 1MHz)
    APA102Full strip(connection, N_PIXELS);                                 // Create APA102 full driver, (connection, n=N_PIXELS pixels)

    // --- 13-bit effective color depth demonstration ---
    // First pass: full hardware brightness (31) for maximum drive current
    // Second pass: hardware brightness 1 (1/31 current) to show hardware vs software dimming

    // --- Pass 1: Full hardware brightness (31) ---
    // Rainbow sweep at hardware brightness 31 uses full 8-bit PWM channels + 5-bit
    // hardware current control = 13-bit effective depth per channel.
    strip.set_brightness(255);                                              // Set global software brightness, (value=0–255) → void
    float hue_offset = 0.0f;
    unsigned long start = 0; // would use clock_gettime in real code
    unsigned long last_print = 0;
    for (unsigned long elapsed_total = 0; elapsed_total < RAINBOW_US; elapsed_total += FRAME_US) {
        for (size_t i = 0; i < N_PIXELS; i++) {
            float h = fmodf(hue_offset + (float)i / N_PIXELS, 1.0f);
            uint8_t r, g, b;
            hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
            strip.set_pixel(i, r, g, b, 31);                                // Set pixel i to rainbow hue at hw brightness 31, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
        }
        strip.show();                                                       // Transmit buffer to strip, () → void
                                                                              // applies software brightness scaling then calls connection.write()
        hue_offset = fmodf(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
        if (elapsed_total - last_print >= 1000000) {
            printf("rainbow hw_brightness=31 hue_offset=%.3f\n", hue_offset);
            last_print = elapsed_total;
        }
        usleep(FRAME_US);
    }

    // --- Pass 2: Low hardware brightness (1) ---
    // Same 8-bit RGB values but hardware brightness=1 (1/31 drive current).
    // Demonstrates hardware current control vs software brightness scaling.
    strip.set_brightness(255);                                              // Set global software brightness, (value=0–255) → void
    hue_offset = 0.0f;
    start = 0;
    last_print = 0;
    for (unsigned long elapsed_total = 0; elapsed_total < RAINBOW_US; elapsed_total += FRAME_US) {
        for (size_t i = 0; i < N_PIXELS; i++) {
            float h = fmodf(hue_offset + (float)i / N_PIXELS, 1.0f);
            uint8_t r, g, b;
            hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
            strip.set_pixel(i, r, g, b, 1);                                 // Set pixel i to rainbow hue at hw brightness 1, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
        }
        strip.show();                                                       // Transmit buffer to strip, () → void
                                                                              // applies software brightness scaling then calls connection.write()
        hue_offset = fmodf(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
        if (elapsed_total - last_print >= 1000000) {
            printf("rainbow hw_brightness=1 hue_offset=%.3f\n", hue_offset);
            last_print = elapsed_total;
        }
        usleep(FRAME_US);
    }

    strip.off();                                                            // Turn off all pixels, () → void
    usleep(1000000);
    return 0;
}

static void hsv_to_rgb(float h, float s, float v,
                        uint8_t& r, uint8_t& g, uint8_t& b) {
    if (s == 0.0f) { r = g = b = (uint8_t)(v * 255); return; }
    int i = (int)(h * 6.0f);
    float f = h * 6.0f - i;
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