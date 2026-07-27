#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "NeoPixelConnectionPicoSDK.h"
#include "SK6812RGBW.h"

int main(void) {
    // SPI0 on GP3 (MOSI) — NeoPixel DIN must be on the SPI MOSI pin;
    // SCK, MISO, and CS are unused by the strip.
    spi_init(spi0, 2'400'000);
    gpio_set_function(3, GPIO_FUNC_SPI);
    NeoPixelConnectionPicoSDK connection(spi0);
    SK6812RGBWFull strip(connection, /*n_pixels=*/8);

    static const size_t N_PIXELS          = 30;
    static const unsigned long FRAME_MS   = 33;   // ~30 fps
    static const unsigned long RAINBOW_MS = 10000;
    static const unsigned long WARM_MS    = 2000;
    static const unsigned long WARM_HALF  = 100;  // 5 Hz

    stdio_init_all();

    strip.set_brightness(180);                 // Set global brightness, (value=0–255) → void
    while (true) {

    // --- Rainbow rotation: each pixel is assigned a hue offset by its position;
    //     the offset advances each frame so the rainbow rotates around the strip.
    //     RGB channels only (w=0) for 10 seconds at ~30 fps. ---
    float hue_offset = 0.0f;
    unsigned long start = to_ms_since_boot(get_absolute_time());
    unsigned long last_print = start;
    while (to_ms_since_boot(get_absolute_time()) - start < RAINBOW_MS) {
        for (size_t i = 0; i < N_PIXELS; i++) {
            float h = fmod(hue_offset + (float)i / N_PIXELS, 1.0f);
            uint8_t r, g, b;
            neopixel_hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
            strip.set_pixel(i, r, g, b, 0);    // Set pixel i to rainbow hue (w=0), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
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

    // --- Warm-white strobe: showcases the dedicated white element.
    //     All four channels active (r=255, g=200, b=150, w=255) gives a warm,
    //     high-CRI white; toggling at 5 Hz for 2 seconds draws the eye to the
    //     difference between mixed-RGB white and the native W element. ---
    strip.set_brightness(255);                 // Set global brightness, (value=0–255) → void
    strip.fill(255, 200, 150, 255);            // Pre-load warm white (RGB+W) into buffer, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    start = to_ms_since_boot(get_absolute_time());
    bool state = true;
    while (to_ms_since_boot(get_absolute_time()) - start < WARM_MS) {
        strip.set_brightness(state ? 255 : 0); // Toggle brightness on/off, (value=0–255) → void
        strip.show();                          // Transmit buffer to strip, () → void
        state = !state;
        delay(WARM_HALF);
    }

    // --- Return to continuous rainbow ---
    strip.set_brightness(180);                 // Set global brightness, (value=0–255) → void
        sleep_ms(10);
    }

    return 0;
}
