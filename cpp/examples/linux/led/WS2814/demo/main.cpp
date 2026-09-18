#include <cstdio>
#include <cmath>
#include <unistd.h>
#include "NeoPixelConnectionLinux.h"
#include "WS2814.h"

static const size_t N_PIXELS         = 30;
static const useconds_t FRAME_US     = 33000;    // ~30 fps
static const useconds_t RAINBOW_US   = 5000000;  // 5 s
static const useconds_t FLASH_US     = 2000000;  // 2 s
static const useconds_t DIM_US       = 2000000;  // 2 s

int main() {
    NeoPixelConnectionLinux connection(0, 0);
    WS2814Full strip(connection, N_PIXELS);                                 // Create WS2814 full driver, (connection, n=N_PIXELS pixels)

    // --- Rainbow rotation using RGB channels (white=0). Each pixel is
    //     assigned a hue offset by its position; the offset advances each
    //     frame so the rainbow rotates continuously around the strip.
    //     Demonstrates that WS2814's RGBW wire order is identity (R, G, B, W
    //     with no reorder), unlike the SK6812RGBW's GRBW order. Runs at
    //     ~30 fps for 5 seconds. ---
    float hue_offset = 0.0f;
    useconds_t elapsed_total = 0;
    useconds_t last_print = 0;
    while (elapsed_total < RAINBOW_US) {
        for (size_t i = 0; i < N_PIXELS; i++) {
            float h = fmodf(hue_offset + (float)i / N_PIXELS, 1.0f);
            uint8_t r, g, b;
            neopixel_hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
            strip.set_pixel(i, r, g, b, 0);                                  // Set pixel i to rainbow hue (w=0), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
        }
        strip.show();                                                       // Transmit buffer to strip, () → void
        hue_offset = fmodf(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
        if (elapsed_total - last_print >= 1000000) {
            printf("mode=rainbow brightness=%u\n", strip.get_brightness());
            last_print = elapsed_total;
        }
        usleep(FRAME_US);
        elapsed_total += FRAME_US;
    }

    // --- Warm white at full brightness for 2 seconds. r=255, g=200, b=150,
    //     w=255 blends the dedicated white element with amber-tinted RGB,
    //     exercising the white channel and the 32-bit RGBW pixel word at
    //     full brightness. ---
    strip.fill(255, 200, 150, 255);                                          // Fill all pixels warm white, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    for (useconds_t t = 0; t < FLASH_US; t += 100000) {
        printf("mode=warm-white brightness=%u\n", strip.get_brightness());
        usleep(100000);
    }

    // --- Dim warm white to 50% using the brightness property and hold for
    //     2 seconds. Demonstrates that brightness scaling is non-destructive:
    //     the stored RGBW values are unchanged, only the scale factor applied
    //     at show() time changes. ---
    strip.set_brightness(128);                                               // Set global brightness, (value=0–255) → void
    strip.show();                                                            // Transmit buffer to strip, () → void
    for (useconds_t t = 0; t < DIM_US; t += 100000) {
        printf("mode=warm-white-dimmed brightness=%u\n", strip.get_brightness());
        usleep(100000);
    }

    // --- Cycle to cool white at full brightness. r=200, g=210, b=255,
    //     w=255 shifts the blend toward blue, showcasing the dedicated
    //     white element paired with a cool-tinted RGB base. ---
    strip.set_brightness(255);                                               // Set global brightness, (value=0–255) → void
    strip.fill(200, 210, 255, 255);                                          // Fill all pixels cool white, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    printf("mode=cool-white brightness=%u\n", strip.get_brightness());
    return 0;
}
