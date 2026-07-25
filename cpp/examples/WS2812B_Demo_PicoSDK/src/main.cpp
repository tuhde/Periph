#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "NeoPixelTransportPicoSDK.h"
#include "WS2812B.h"

// SPI0 on GP3 (MOSI) — NeoPixel DIN must be on the SPI MOSI pin;
// SCK, MISO, and CS are unused by the strip.
spi_init(spi0, 2'400'000);
gpio_set_function(3, GPIO_FUNC_SPI);
NeoPixelTransportPicoSDK transport(spi0);
WS2812BFull strip(transport, /*n_pixels=*/8);

int main(void) {
    static const size_t N_PIXELS          = 30;
    static const unsigned long FRAME_MS   = 33;   // ~30 fps
    static const unsigned long RAINBOW_MS = 10000;
    static const unsigned long STROBE_MS  = 2000;
    static const unsigned long STROBE_HALF_MS = 50;
                            uint8_t& r, uint8_t& g, uint8_t& b);

    stdio_init_all();

    strip.set_brightness(180);                 // Set global brightness, (value=0–255) → void
    while (true) {

    // --- Rainbow rotation: each pixel is assigned a hue offset by its position;
    //     the offset is advanced each frame so the rainbow rotates around the strip.
    //     Running at ~30 fps for 10 seconds gives a smooth continuous animation. ---
    float hue_offset = 0.0f;
    unsigned long start = to_ms_since_boot(get_absolute_time());
    unsigned long last_print = start;
    while (to_ms_since_boot(get_absolute_time()) - start < RAINBOW_MS) {
        for (size_t i = 0; i < N_PIXELS; i++) {
            float h = fmod(hue_offset + (float)i / N_PIXELS, 1.0f);
            uint8_t r, g, b;
            hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
            strip.set_pixel(i, r, g, b);       // Set pixel i to rainbow hue, (index=0–n-1, r=0–255, g=0–255, b=0–255) → void
        }
        strip.show();                          // Transmit buffer to strip, () → void
        hue_offset = fmod(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
        unsigned long now = to_ms_since_boot(get_absolute_time());
        if (now - last_print >= 1000) {
            printf("rainbow hue_offset=");
            printf("%.3f\n", hue_offset);
            last_print = now;
        }
        unsigned long elapsed = to_ms_since_boot(get_absolute_time()) - now;
        if (elapsed < FRAME_MS) delay(FRAME_MS - elapsed);
    }

    // --- Strobe effect: alternate full white and off at 10 Hz for 2 seconds.
    //     Uses brightness=255 for maximum intensity then brightness=0 for off,
    //     demonstrating non-destructive brightness scaling — pixel values in the
    //     buffer are never zeroed. ---
    strip.set_brightness(255);                 // Set global brightness, (value=0–255) → void
    strip.fill(255, 255, 255);                 // Pre-load white into buffer, (r=0–255, g=0–255, b=0–255) → void
    start = to_ms_since_boot(get_absolute_time());
    bool state = true;
    while (to_ms_since_boot(get_absolute_time()) - start < STROBE_MS) {
        strip.set_brightness(state ? 255 : 0); // Set global brightness, (value=0–255) → void
        strip.show();                          // Transmit buffer to strip, () → void
        state = !state;
        delay(STROBE_HALF_MS);
    }

    // --- Return to continuous rainbow ---
    strip.set_brightness(180);                 // Set global brightness, (value=0–255) → void
        sleep_ms(10);
    }

    return 0;
}
